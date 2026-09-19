package com.sosync.service.incident

import com.sosync.common.NotFoundException
import com.sosync.config.SosyncProperties
import com.sosync.domain.Incident
import com.sosync.domain.IncidentStatus
import com.sosync.domain.User
import com.sosync.persistence.EmergencyContactRepository
import com.sosync.persistence.FamilyMemberRepository
import com.sosync.persistence.IncidentEventRepository
import com.sosync.persistence.IncidentRepository
import com.sosync.persistence.LocationUpdateRepository
import com.sosync.persistence.NotificationRepository
import com.sosync.persistence.ResponderRepository
import com.sosync.persistence.UserRepository
import com.sosync.service.admin.AuditService
import com.sosync.service.responder.ResponderMatcher
import com.sosync.web.dto.IncidentResponse
import com.sosync.web.dto.LocationResponse
import com.sosync.web.dto.NotificationResponse
import com.sosync.web.dto.PersonSummary
import com.sosync.web.dto.ResponderAlertResponse
import com.sosync.web.dto.ResponderSummary
import com.sosync.web.dto.TimelineEntryResponse
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * Reads over incidents.
 *
 * Kept apart from [IncidentService] following the Readers command/query convention. The rule
 * that matters here is the second one from that codebase: hydration is batched, never per row.
 * A list of twenty alerts resolves its reporters in one query, because a responder list that
 * issues a query per row is a list that times out on the worst network, which is the network
 * this is for.
 */
@Service
class IncidentQueryService(
    private val incidentRepository: IncidentRepository,
    private val eventRepository: IncidentEventRepository,
    private val locationRepository: LocationUpdateRepository,
    private val notificationRepository: NotificationRepository,
    private val userRepository: UserRepository,
    private val responderRepository: ResponderRepository,
    private val contactRepository: EmergencyContactRepository,
    private val familyMemberRepository: FamilyMemberRepository,
    private val accessPolicy: IncidentAccessPolicy,
    private val responderMatcher: ResponderMatcher,
    private val auditService: AuditService,
    private val properties: SosyncProperties,
) {

    /**
     * One incident in full, with the timeline and the delivery log.
     *
     * A viewer who is entitled to the live position has that access recorded. "Who saw where I
     * was" is a question the system should be able to answer, and it can only answer it if
     * reads are audited rather than just writes.
     */
    @Transactional
    fun detail(incidentId: String, viewerId: String, includeTrail: Boolean): IncidentResponse {
        val (incident, relationship) = accessPolicy.requireViewable(incidentId, viewerId)
        val canViewLocation = accessPolicy.canViewLocation(incident, relationship)

        if (canViewLocation && relationship != ViewerRelationship.REPORTER) {
            auditService.record(
                actorId = viewerId,
                action = AuditService.LOCATION_VIEWED,
                targetType = "incident",
                targetId = incidentId,
                detail = "as $relationship",
            )
        }

        val reporter = userRepository.findById(incident.userId).orElseThrow {
            NotFoundException("No such incident")
        }

        val trail = if (includeTrail && canViewLocation) {
            locationRepository.findAllByIncidentIdOrderByRecordedAtAsc(incidentId)
                .map { LocationResponse.from(it) }
        } else {
            null
        }

        // Contacts see who was told; a responder does not need the reporter's whole social graph.
        val notifications = if (
            relationship in setOf(
                ViewerRelationship.REPORTER,
                ViewerRelationship.TRUSTED_CONTACT,
                ViewerRelationship.FAMILY_MEMBER,
                ViewerRelationship.ADMIN,
            )
        ) {
            notificationRepository.findAllByIncidentIdOrderByCreatedAtAsc(incidentId)
                .map { NotificationResponse.from(it) }
        } else {
            null
        }

        return IncidentResponse.from(
            incident = incident,
            reporter = reporter,
            relationship = relationship,
            canViewLocation = canViewLocation,
            assignedResponder = assignedResponderSummary(incident),
            locationPointCount = locationRepository.countByIncidentId(incidentId),
            locationTrail = trail,
            timeline = eventRepository.findAllByIncidentIdOrderByOccurredAtAsc(incidentId)
                .map { TimelineEntryResponse.from(it) },
            notifications = notifications,
        )
    }

    /** The caller's own open emergency, if they have one. */
    @Transactional(readOnly = true)
    fun activeForUser(userId: String): IncidentResponse? {
        val incident = incidentRepository
            .findFirstByUserIdAndStatusInOrderByTriggeredAtDesc(userId, IncidentStatus.OPEN)
            ?: return null
        return summarise(incident, userId, ViewerRelationship.REPORTER)
    }

    @Transactional(readOnly = true)
    fun historyForUser(userId: String, page: Int, size: Int): Page<IncidentResponse> {
        val incidents = incidentRepository.findAllByUserIdOrderByTriggeredAtDesc(
            userId,
            PageRequest.of(page, size),
        )
        val reporter = userRepository.findById(userId).orElseThrow {
            NotFoundException("No such user")
        }
        val responderSummaries = responderSummariesFor(incidents.content)

        return incidents.map { incident ->
            IncidentResponse.from(
                incident = incident,
                reporter = reporter,
                relationship = ViewerRelationship.REPORTER,
                canViewLocation = true,
                assignedResponder = incident.assignedResponderId?.let { responderSummaries[it] },
            )
        }
    }

    /**
     * Incidents the caller is being watched over: the ones where they are a trusted contact or
     * a family member.
     *
     * This is the trusted-contact inbox. Matched on phone number, so nominating somebody works
     * whether or not they had an account at the time.
     */
    @Transactional(readOnly = true)
    fun alertsForContact(viewerId: String, includeClosed: Boolean): List<IncidentResponse> {
        val viewer = userRepository.findById(viewerId).orElseThrow {
            NotFoundException("No such user")
        }

        val nominatedBy = contactRepository.findAllByPhone(viewer.phone).map { it.userId }.toSet()
        val familyPeers = familyPeerIds(viewerId)
        val watchedUserIds = nominatedBy + familyPeers

        if (watchedUserIds.isEmpty()) return emptyList()

        val statuses = if (includeClosed) {
            IncidentStatus.entries.toSet()
        } else {
            IncidentStatus.OPEN
        }

        val incidents = incidentRepository
            .findAllByUserIdInAndStatusInOrderByTriggeredAtDesc(watchedUserIds, statuses)

        if (incidents.isEmpty()) return emptyList()

        val reporters = userRepository
            .findAllByIdIn(incidents.map { it.userId }.distinct())
            .associateBy { it.id }
        val responderSummaries = responderSummariesFor(incidents)

        return incidents.mapNotNull { incident ->
            val reporter = reporters[incident.userId] ?: return@mapNotNull null
            val relationship = if (incident.userId in nominatedBy) {
                ViewerRelationship.TRUSTED_CONTACT
            } else {
                ViewerRelationship.FAMILY_MEMBER
            }
            IncidentResponse.from(
                incident = incident,
                reporter = reporter,
                relationship = relationship,
                canViewLocation = accessPolicy.canViewLocation(incident, relationship),
                assignedResponder = incident.assignedResponderId?.let { responderSummaries[it] },
                locationPointCount = 0,
            )
        }
    }

    /**
     * The responder alert list: unclaimed incidents within range, plus whatever this responder
     * is already holding.
     *
     * Sorted by how long the alert has been waiting rather than by distance. The nearest alert
     * is not the most urgent one; the one nobody has taken for four minutes is.
     */
    @Transactional(readOnly = true)
    fun alertsForResponder(responderUserId: String): List<ResponderAlertResponse> {
        val responder = responderRepository.findByUserId(responderUserId)
            ?: throw NotFoundException("You are not registered as a responder")

        val unclaimed = if (responder.isDispatchable) {
            incidentRepository.findAllByStatusOrderByTriggeredAtAsc(IncidentStatus.TRIGGERED)
        } else {
            // An unverified or unavailable responder sees nothing new, only what they hold.
            emptyList()
        }

        val mine = incidentRepository
            .findAllByAssignedResponderIdOrderByTriggeredAtDesc(
                responder.id,
                PageRequest.of(0, 20),
            )
            .content
            .filter { it.isOpen }

        val combined = (mine + unclaimed).distinctBy { it.id }
        if (combined.isEmpty()) return emptyList()

        val reporters = userRepository
            .findAllByIdIn(combined.map { it.userId }.distinct())
            .associateBy { it.id }

        val now = Instant.now()
        return combined
            .mapNotNull { incident ->
                val reporter = reporters[incident.userId] ?: return@mapNotNull null
                val distance = responderMatcher.distanceTo(responder, incident)
                val isMine = incident.assignedResponderId == responder.id

                // An unclaimed alert further away than the dispatch radius is somebody else's
                // to take. An incident this responder already holds stays on their list however
                // far they have travelled from it.
                if (!isMine &&
                    distance != null &&
                    distance > properties.location.nearbyRadiusKm
                ) {
                    return@mapNotNull null
                }

                ResponderAlertResponse(
                    incidentId = incident.id,
                    status = incident.status,
                    reporter = PersonSummary.from(reporter),
                    location = latestLocationOf(incident),
                    distanceKm = distance,
                    triggeredAt = incident.triggeredAt,
                    waitingSeconds = Duration.between(incident.triggeredAt, now)
                        .seconds
                        .coerceAtLeast(0),
                    note = incident.note,
                    isMine = isMine,
                )
            }
            .sortedWith(compareByDescending<ResponderAlertResponse> { it.isMine }
                .thenByDescending { it.waitingSeconds })
    }

    @Transactional(readOnly = true)
    fun historyForResponder(
        responderUserId: String,
        page: Int,
        size: Int,
    ): Page<IncidentResponse> {
        val responder = responderRepository.findByUserId(responderUserId)
            ?: throw NotFoundException("You are not registered as a responder")

        val incidents = incidentRepository.findAllByAssignedResponderIdOrderByTriggeredAtDesc(
            responder.id,
            PageRequest.of(page, size),
        )
        val reporters = userRepository
            .findAllByIdIn(incidents.content.map { it.userId }.distinct())
            .associateBy { it.id }
        val summary = ResponderSummary.from(
            responder,
            userRepository.findById(responder.userId).orElse(null),
        )

        return incidents.map { incident ->
            IncidentResponse.from(
                incident = incident,
                reporter = reporters.getValue(incident.userId),
                relationship = ViewerRelationship.ASSIGNED_RESPONDER,
                canViewLocation = accessPolicy.canViewLocation(
                    incident,
                    ViewerRelationship.ASSIGNED_RESPONDER,
                ),
                assignedResponder = summary,
            )
        }
    }

    /** Admin view over every incident, newest first. */
    @Transactional(readOnly = true)
    fun allIncidents(status: IncidentStatus?, page: Int, size: Int): Page<IncidentResponse> {
        val statuses = status?.let { setOf(it) } ?: IncidentStatus.entries.toSet()
        val incidents = incidentRepository.findAllByStatusInOrderByTriggeredAtDesc(
            statuses,
            PageRequest.of(page, size),
        )
        val reporters = userRepository
            .findAllByIdIn(incidents.content.map { it.userId }.distinct())
            .associateBy { it.id }
        val responderSummaries = responderSummariesFor(incidents.content)

        return incidents.map { incident ->
            IncidentResponse.from(
                incident = incident,
                reporter = reporters.getValue(incident.userId),
                relationship = ViewerRelationship.ADMIN,
                canViewLocation = true,
                assignedResponder = incident.assignedResponderId?.let { responderSummaries[it] },
            )
        }
    }

    @Transactional(readOnly = true)
    fun locationTrail(incidentId: String, viewerId: String): List<LocationResponse> {
        val (incident, relationship) = accessPolicy.requireViewable(incidentId, viewerId)
        if (!accessPolicy.canViewLocation(incident, relationship)) {
            // Not an error: the incident is closed and sharing has stopped, which is the
            // promised behaviour rather than a failure.
            return emptyList()
        }

        if (relationship != ViewerRelationship.REPORTER) {
            auditService.record(
                actorId = viewerId,
                action = AuditService.LOCATION_VIEWED,
                targetType = "incident",
                targetId = incidentId,
                detail = "trail, as $relationship",
            )
        }

        return locationRepository.findAllByIncidentIdOrderByRecordedAtAsc(incidentId)
            .map { LocationResponse.from(it) }
    }

    @Transactional(readOnly = true)
    fun summarise(
        incident: Incident,
        viewerId: String,
        relationship: ViewerRelationship,
    ): IncidentResponse {
        val reporter = userRepository.findById(incident.userId).orElseThrow {
            NotFoundException("No such incident")
        }
        return IncidentResponse.from(
            incident = incident,
            reporter = reporter,
            relationship = relationship,
            canViewLocation = accessPolicy.canViewLocation(incident, relationship),
            assignedResponder = assignedResponderSummary(incident),
            locationPointCount = locationRepository.countByIncidentId(incident.id),
            timeline = eventRepository.findAllByIncidentIdOrderByOccurredAtAsc(incident.id)
                .map { TimelineEntryResponse.from(it) },
        )
    }

    private fun latestLocationOf(incident: Incident): LocationResponse? {
        val at = incident.lastLocationAt ?: return null
        val lat = incident.lastLat ?: return null
        val lng = incident.lastLng ?: return null
        return LocationResponse.of(lat, lng, null, at)
    }

    private fun assignedResponderSummary(incident: Incident): ResponderSummary? {
        val responderId = incident.assignedResponderId ?: return null
        val responder = responderRepository.findById(responderId).orElse(null) ?: return null
        val account = userRepository.findById(responder.userId).orElse(null)
        return ResponderSummary.from(
            responder,
            account,
            responderMatcher.distanceTo(responder, incident),
        )
    }

    /** Batched: one query for every responder referenced by a page of incidents. */
    private fun responderSummariesFor(incidents: List<Incident>): Map<String, ResponderSummary> {
        val ids = incidents.mapNotNull { it.assignedResponderId }.distinct()
        if (ids.isEmpty()) return emptyMap()

        val responders = responderRepository.findAllByIdIn(ids)
        val accounts = userRepository
            .findAllByIdIn(responders.map { it.userId })
            .associateBy { it.id }

        return responders.associate { it.id to ResponderSummary.from(it, accounts[it.userId]) }
    }

    private fun familyPeerIds(userId: String): Set<String> {
        val familyIds = familyMemberRepository.findAllByUserId(userId).map { it.familyId }
        if (familyIds.isEmpty()) return emptySet()
        return familyIds
            .flatMap { familyMemberRepository.findAllByFamilyId(it) }
            .map { it.userId }
            .filter { it != userId }
            .toSet()
    }

    @Transactional(readOnly = true)
    fun requireUser(userId: String): User = userRepository.findById(userId).orElseThrow {
        NotFoundException("No such user")
    }
}
