package com.sosync.service.incident

import com.sosync.common.BadRequestException
import com.sosync.common.ConflictException
import com.sosync.common.ForbiddenException
import com.sosync.common.Geo
import com.sosync.common.InvalidSafetyPinException
import com.sosync.common.NotFoundException
import com.sosync.domain.Incident
import com.sosync.domain.IncidentEvent
import com.sosync.domain.IncidentEventType
import com.sosync.domain.IncidentStatus
import com.sosync.domain.LocationUpdate
import com.sosync.domain.NotificationRecord
import com.sosync.domain.NotificationType
import com.sosync.domain.Responder
import com.sosync.domain.User
import com.sosync.persistence.IncidentEventRepository
import com.sosync.persistence.IncidentRepository
import com.sosync.persistence.LocationUpdateRepository
import com.sosync.persistence.ResponderRepository
import com.sosync.persistence.UserRepository
import com.sosync.service.admin.AuditService
import com.sosync.service.notification.NotificationDispatcher
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

private val log = KotlinLogging.logger {}

/** An incident plus the deliveries it generated, so the caller can show who was reached. */
data class TriggerResult(
    val incident: Incident,
    val notifications: List<NotificationRecord>,
    val alreadyActive: Boolean,
)

/**
 * Writes to incidents: raising, progressing and closing an emergency.
 *
 * The command/query split follows the Readers convention. Reads live in [IncidentQueryService];
 * everything that changes state is here, and every change appends to the timeline rather than
 * only overwriting a column.
 */
@Service
class IncidentService(
    private val incidentRepository: IncidentRepository,
    private val eventRepository: IncidentEventRepository,
    private val locationRepository: LocationUpdateRepository,
    private val userRepository: UserRepository,
    private val responderRepository: ResponderRepository,
    private val dispatcher: NotificationDispatcher,
    private val accessPolicy: IncidentAccessPolicy,
    private val passwordEncoder: PasswordEncoder,
    private val auditService: AuditService,
) {

    /**
     * Raise an emergency.
     *
     * Pressing the button twice returns the incident already running rather than creating a
     * second one, and says so via [TriggerResult.alreadyActive]. A frightened person presses
     * more than once; one emergency must not become three, or the responders split up. The
     * partial unique index in the schema is the real guarantee, and the catch below turns the
     * race into the same idempotent answer.
     *
     * A missing GPS fix does not block the alert. Reaching people late with a location beats
     * reaching them never because the phone was indoors.
     */
    @Transactional
    fun trigger(
        userId: String,
        latitude: Double?,
        longitude: Double?,
        accuracy: Double?,
        silent: Boolean,
        note: String?,
    ): TriggerResult {
        val reporter = userRepository.findById(userId).orElseThrow {
            NotFoundException("No such user")
        }

        incidentRepository
            .findFirstByUserIdAndStatusInOrderByTriggeredAtDesc(userId, IncidentStatus.OPEN)
            ?.let { existing ->
                log.info { "Repeat SOS from $userId; returning active incident ${existing.id}" }
                // Still record the position: a second press is often a later, better fix.
                if (Geo.isPlausible(latitude, longitude)) {
                    appendLocation(existing, latitude!!, longitude!!, accuracy, Instant.now())
                    incidentRepository.save(existing)
                }
                return TriggerResult(existing, emptyList(), alreadyActive = true)
            }

        val hasFix = Geo.isPlausible(latitude, longitude)
        if (!hasFix && latitude != null) {
            log.warn { "SOS from $userId carried an implausible fix ($latitude, $longitude)" }
        }

        val incident = Incident(
            userId = userId,
            status = IncidentStatus.TRIGGERED,
            silent = silent,
            note = note?.take(500),
            triggerLat = if (hasFix) latitude else null,
            triggerLng = if (hasFix) longitude else null,
            triggerAccuracy = if (hasFix) accuracy else null,
            lastLat = if (hasFix) latitude else null,
            lastLng = if (hasFix) longitude else null,
            lastLocationAt = if (hasFix) Instant.now() else null,
        )

        val saved = try {
            incidentRepository.save(incident)
        } catch (ex: DataIntegrityViolationException) {
            // Lost the race against a concurrent press. The index did its job; answer with the
            // incident that won instead of failing the person holding the button.
            val active = incidentRepository
                .findFirstByUserIdAndStatusInOrderByTriggeredAtDesc(userId, IncidentStatus.OPEN)
                ?: throw ConflictException("Could not raise the alert. Try again.")
            return TriggerResult(active, emptyList(), alreadyActive = true)
        }

        if (hasFix) {
            locationRepository.save(
                LocationUpdate(
                    incidentId = saved.id,
                    latitude = latitude!!,
                    longitude = longitude!!,
                    accuracy = accuracy,
                    recordedAt = Instant.now(),
                ),
            )
        }

        appendEvent(
            saved,
            IncidentEventType.TRIGGERED,
            actorId = userId,
            actorLabel = reporter.fullName,
            notes = if (hasFix) "SOS raised with GPS fix" else "SOS raised without a GPS fix",
        )

        val notifications = dispatcher.dispatchSosTriggered(saved, reporter)

        if (notifications.isNotEmpty()) {
            appendEvent(
                saved,
                IncidentEventType.NOTIFIED,
                actorId = null,
                actorLabel = "SOSync",
                notes = "${notifications.size} deliveries to " +
                    "${notifications.mapNotNull { it.recipientLabel }.distinct().size} recipients",
            )
        }

        auditService.record(
            actorId = userId,
            action = AuditService.INCIDENT_TRIGGERED,
            targetType = "incident",
            targetId = saved.id,
            detail = "silent=$silent, hasFix=$hasFix, deliveries=${notifications.size}",
        )

        log.info { "Incident ${saved.id} raised by $userId" }
        return TriggerResult(saved, notifications, alreadyActive = false)
    }

    /**
     * A responder takes ownership of an unclaimed incident.
     *
     * First to accept wins. The status check inside the transaction is what makes that safe:
     * two responders tapping accept at the same moment cannot both succeed, and the second is
     * told who has it rather than silently overwriting them.
     */
    @Transactional
    fun accept(incidentId: String, responderUserId: String): Incident {
        val responder = dispatchableResponder(responderUserId)
        val incident = incidentRepository.findById(incidentId).orElseThrow {
            NotFoundException("No such incident")
        }

        if (incident.status != IncidentStatus.TRIGGERED) {
            throw ConflictException(
                when (incident.status) {
                    IncidentStatus.RESOLVED -> "That incident is already resolved"
                    IncidentStatus.CANCELLED -> "That alert was cancelled"
                    else -> "Another responder has already taken that incident"
                },
            )
        }

        val now = Instant.now()
        incident.status = IncidentStatus.ACCEPTED
        incident.assignedResponderId = responder.id
        incident.acceptedAt = now
        incident.updatedAt = now
        incidentRepository.save(incident)

        appendEvent(
            incident,
            IncidentEventType.ACCEPTED,
            actorId = responderUserId,
            actorLabel = responder.organization,
            notes = "Accepted by ${responder.organization}",
        )

        notifyStatus(incident, NotificationType.RESPONDER_ACCEPTED, responder.organization)

        auditService.record(
            actorId = responderUserId,
            action = AuditService.INCIDENT_ACCEPTED,
            targetType = "incident",
            targetId = incident.id,
            detail = "responder=${responder.id} (${responder.organization})",
        )

        log.info { "Incident $incidentId accepted by responder ${responder.id}" }
        return incident
    }

    /**
     * Move an accepted incident along: RESPONDING, ARRIVED, RESOLVED.
     *
     * Forward only, and only by the responder holding it. Allowing a step backwards would make
     * the timeline unreadable, which defeats the point of having one.
     */
    @Transactional
    fun advance(
        incidentId: String,
        responderUserId: String,
        target: IncidentStatus,
        note: String?,
    ): Incident {
        val responder = responderRepository.findByUserId(responderUserId)
            ?: throw ForbiddenException("You are not registered as a responder")

        val incident = incidentRepository.findById(incidentId).orElseThrow {
            NotFoundException("No such incident")
        }

        if (incident.assignedResponderId != responder.id) {
            throw ForbiddenException("That incident is assigned to another responder")
        }
        if (!incident.isOpen) {
            throw ConflictException("That incident is already closed")
        }
        if (target !in ALLOWED_RESPONDER_TRANSITIONS.getValue(incident.status)) {
            throw BadRequestException(
                "Cannot move an incident from ${incident.status} to $target",
            )
        }

        val now = Instant.now()
        incident.status = target
        incident.updatedAt = now
        when (target) {
            IncidentStatus.ARRIVED -> incident.arrivedAt = now
            IncidentStatus.RESOLVED -> {
                incident.closedAt = now
                incident.resolutionNote = note?.take(500)
            }
            else -> Unit
        }
        incidentRepository.save(incident)

        val eventType = when (target) {
            IncidentStatus.RESPONDING -> IncidentEventType.RESPONDING
            IncidentStatus.ARRIVED -> IncidentEventType.ARRIVED
            IncidentStatus.RESOLVED -> IncidentEventType.RESOLVED
            else -> IncidentEventType.ACCEPTED
        }
        appendEvent(
            incident,
            eventType,
            actorId = responderUserId,
            actorLabel = responder.organization,
            notes = note,
        )

        val notificationType = when (target) {
            IncidentStatus.RESPONDING -> NotificationType.RESPONDER_RESPONDING
            IncidentStatus.ARRIVED -> NotificationType.RESPONDER_ARRIVED
            IncidentStatus.RESOLVED -> NotificationType.INCIDENT_RESOLVED
            else -> NotificationType.RESPONDER_ACCEPTED
        }
        notifyStatus(incident, notificationType, responder.organization)

        auditService.record(
            actorId = responderUserId,
            action = AuditService.INCIDENT_STATUS_CHANGED,
            targetType = "incident",
            targetId = incident.id,
            detail = "-> $target",
        )

        return incident
    }

    /**
     * Stand down an emergency.
     *
     * This is the most security-sensitive operation in the system, because whoever can cancel an
     * alert can call off the response. Three rules follow from that:
     *
     * 1. Only the reporter can cancel. Not a contact, not a responder, not an admin.
     * 2. The safety PIN is required. Someone who grabs an unlocked phone has the app but not
     *    the PIN, so they cannot silence it.
     * 3. A wrong PIN leaves the incident running. It is reported as a distinct error so the app
     *    can stay outwardly calm while help continues on its way, because the person typing may
     *    not be the person who raised the alarm.
     *
     * Where no PIN has been set, the account password is accepted instead, so an account that
     * has not finished setup is not left unable to cancel at all.
     */
    @Transactional
    fun cancel(incidentId: String, userId: String, pin: String, reason: String?): Incident {
        val incident = accessPolicy.requireReporter(incidentId, userId)
        if (!incident.isOpen) {
            throw ConflictException("That incident is already closed")
        }

        val user = userRepository.findById(userId).orElseThrow {
            NotFoundException("No such user")
        }

        val expectedHash = user.safetyPinHash ?: user.passwordHash
        if (!passwordEncoder.matches(pin, expectedHash)) {
            // Its own transaction: this method is about to throw, and an audit row written in
            // the caller's transaction would roll back with it. The refused attempt is the
            // entry most worth keeping.
            auditService.recordRefusal(
                actorId = userId,
                action = AuditService.INCIDENT_CANCELLED,
                targetType = "incident",
                targetId = incidentId,
                detail = "rejected: incorrect safety PIN",
            )
            log.warn { "Failed cancellation attempt on incident $incidentId" }
            throw InvalidSafetyPinException()
        }

        val now = Instant.now()
        incident.status = IncidentStatus.CANCELLED
        incident.cancelReason = reason?.take(200) ?: "Cancelled by user"
        incident.closedAt = now
        incident.updatedAt = now
        incidentRepository.save(incident)

        appendEvent(
            incident,
            IncidentEventType.CANCELLED,
            actorId = userId,
            actorLabel = user.fullName,
            notes = incident.cancelReason,
        )

        val responderLabel = incident.assignedResponderId
            ?.let { responderRepository.findById(it).orElse(null) }
            ?.organization

        notifyStatus(incident, NotificationType.INCIDENT_CANCELLED, responderLabel)

        auditService.record(
            actorId = userId,
            action = AuditService.INCIDENT_CANCELLED,
            targetType = "incident",
            targetId = incidentId,
            detail = "accepted: ${incident.cancelReason}",
        )

        log.info { "Incident $incidentId cancelled by reporter" }
        return incident
    }

    /**
     * Record a position during an active incident.
     *
     * Rejected once the incident closes. This is the mechanism behind the promise that location
     * sharing stops when the emergency ends: there is no code path that stores a position
     * outside an open incident, so the app cannot keep reporting by accident and the server
     * would refuse it if it tried.
     */
    @Transactional
    fun recordLocation(
        incidentId: String,
        userId: String,
        latitude: Double,
        longitude: Double,
        accuracy: Double?,
        recordedAt: Instant?,
    ): Incident {
        val incident = accessPolicy.requireReporter(incidentId, userId)

        if (!incident.isOpen) {
            throw ConflictException(
                "That incident is closed, so location sharing has stopped",
            )
        }
        if (!Geo.isPlausible(latitude, longitude)) {
            throw BadRequestException("Those coordinates are not a usable position")
        }

        appendLocation(incident, latitude, longitude, accuracy, recordedAt ?: Instant.now())
        incidentRepository.save(incident)

        auditService.record(
            actorId = userId,
            action = AuditService.LOCATION_REPORTED,
            targetType = "incident",
            targetId = incidentId,
        )

        return incident
    }

    /**
     * Mark a notification as seen by its recipient.
     *
     * Acknowledgement matters more than it looks: it is the difference between "we sent an
     * alert" and "somebody who can help knows".
     */
    @Transactional
    fun acknowledgeOnTimeline(incident: Incident, actor: User, label: String?) {
        appendEvent(
            incident,
            IncidentEventType.ACKNOWLEDGED,
            actorId = actor.id,
            actorLabel = label ?: actor.fullName,
            notes = "Alert seen",
        )
    }

    private fun appendLocation(
        incident: Incident,
        latitude: Double,
        longitude: Double,
        accuracy: Double?,
        recordedAt: Instant,
    ) {
        locationRepository.save(
            LocationUpdate(
                incidentId = incident.id,
                latitude = latitude,
                longitude = longitude,
                accuracy = accuracy,
                recordedAt = recordedAt,
            ),
        )

        // Only move the denormalised position forward. An out-of-order upload from a phone that
        // was offline must not overwrite a newer fix with an older one.
        val current = incident.lastLocationAt
        if (current == null || recordedAt.isAfter(current)) {
            incident.lastLat = latitude
            incident.lastLng = longitude
            incident.lastLocationAt = recordedAt
        }
        incident.updatedAt = Instant.now()
    }

    private fun appendEvent(
        incident: Incident,
        type: IncidentEventType,
        actorId: String?,
        actorLabel: String?,
        notes: String?,
    ) {
        eventRepository.save(
            IncidentEvent(
                incidentId = incident.id,
                eventType = type,
                actorId = actorId,
                actorLabel = actorLabel,
                notes = notes?.take(500),
            ),
        )
    }

    private fun notifyStatus(
        incident: Incident,
        type: NotificationType,
        responderLabel: String?,
    ) {
        val reporter = userRepository.findById(incident.userId).orElse(null) ?: return
        dispatcher.dispatchStatusChange(incident, reporter, type, responderLabel)
    }

    private fun dispatchableResponder(responderUserId: String): Responder {
        val responder = responderRepository.findByUserId(responderUserId)
            ?: throw ForbiddenException("You are not registered as a responder")

        // Verification is checked at the point of action, not only at alert time. A responder
        // disabled after an alert went out must not still be able to claim it.
        if (!responder.isDispatchable) {
            throw ForbiddenException(
                "Your responder account is ${responder.verificationStatus} and " +
                    "${responder.availabilityStatus}. Only a verified, available responder can " +
                    "accept an incident.",
            )
        }
        return responder
    }

    companion object {
        /** Forward-only transitions a responder may make. */
        private val ALLOWED_RESPONDER_TRANSITIONS: Map<IncidentStatus, Set<IncidentStatus>> = mapOf(
            IncidentStatus.TRIGGERED to emptySet(),
            IncidentStatus.ACCEPTED to setOf(
                IncidentStatus.RESPONDING,
                IncidentStatus.ARRIVED,
                IncidentStatus.RESOLVED,
            ),
            IncidentStatus.RESPONDING to setOf(
                IncidentStatus.ARRIVED,
                IncidentStatus.RESOLVED,
            ),
            IncidentStatus.ARRIVED to setOf(IncidentStatus.RESOLVED),
            IncidentStatus.RESOLVED to emptySet(),
            IncidentStatus.CANCELLED to emptySet(),
        )
    }
}
