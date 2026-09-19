package com.sosync.web.dto

import com.sosync.common.Geo
import com.sosync.domain.AccountStatus
import com.sosync.domain.DeliveryStatus
import com.sosync.domain.EmergencyContact
import com.sosync.domain.Incident
import com.sosync.domain.IncidentEvent
import com.sosync.domain.IncidentEventType
import com.sosync.domain.IncidentStatus
import com.sosync.domain.Language
import com.sosync.domain.LocationUpdate
import com.sosync.domain.NotificationChannel
import com.sosync.domain.NotificationRecord
import com.sosync.domain.NotificationType
import com.sosync.domain.PlanType
import com.sosync.domain.Responder
import com.sosync.domain.ResponderAvailability
import com.sosync.domain.ResponderVerificationStatus
import com.sosync.domain.Role
import com.sosync.domain.Subscription
import com.sosync.domain.SubscriptionStatus
import com.sosync.domain.User
import com.sosync.service.auth.TokenPair
import com.sosync.service.incident.ViewerRelationship
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

// ══════════════════════════════════════════════════════════════════════════════════
// Accounts
// ══════════════════════════════════════════════════════════════════════════════════

data class UserResponse(
    val id: String,
    val fullName: String,
    val phone: String,
    val email: String?,
    val role: Role,
    val status: AccountStatus,
    val phoneVerified: Boolean,
    @Schema(description = "Language this account receives alerts in.")
    val preferredLanguage: Language,
    val emergencyNote: String?,
    val photoUrl: String?,
    @Schema(description = "Whether a cancellation PIN has been set. The PIN itself is never returned.")
    val safetyPinSet: Boolean,
    val createdAt: Instant,
) {
    companion object {
        fun from(user: User) = UserResponse(
            id = user.id,
            fullName = user.fullName,
            phone = user.phone,
            email = user.email,
            role = user.role,
            status = user.status,
            phoneVerified = user.phoneVerified,
            preferredLanguage = user.preferredLanguage,
            emergencyNote = user.emergencyNote,
            photoUrl = user.photoUrl,
            safetyPinSet = user.safetyPinHash != null,
            createdAt = user.createdAt,
        )
    }
}

/**
 * Minimal identity for someone who appears inside another payload: a reporter on an alert, an
 * actor on a timeline. Deliberately small. A responder needs the name, the number to call and
 * any note that helps them find the person, and has no business seeing the rest of the profile.
 */
data class PersonSummary(
    val id: String,
    val fullName: String,
    val phone: String,
    val emergencyNote: String?,
    val photoUrl: String?,
) {
    companion object {
        fun from(user: User) = PersonSummary(
            id = user.id,
            fullName = user.fullName,
            phone = user.phone,
            emergencyNote = user.emergencyNote,
            photoUrl = user.photoUrl,
        )
    }
}

data class AuthResponse(
    val user: UserResponse,
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
    val tokenType: String,
    @Schema(
        description = "Present only while there is no SMS gateway connected. The code that " +
            "would have been texted, so the flow can be completed in the prototype.",
    )
    val simulatedVerificationCode: String? = null,
) {
    companion object {
        fun from(user: User, tokens: TokenPair, verificationCode: String? = null) = AuthResponse(
            user = UserResponse.from(user),
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            expiresInSeconds = tokens.expiresInSeconds,
            tokenType = tokens.tokenType,
            simulatedVerificationCode = verificationCode,
        )
    }
}

data class ContactResponse(
    val id: String,
    val name: String,
    val phone: String,
    val relationship: String?,
    val priority: Int,
    val notificationEnabled: Boolean,
    @Schema(
        description = "Language override for this contact. Null means they are alerted in the " +
            "language of whoever raised the alert. Ignored where the contact has an account, " +
            "since their own preference wins.",
    )
    val language: Language?,
    @Schema(
        description = "True when this number belongs to an SOSync account, meaning the contact " +
            "also gets in-app alerts and can watch the incident. False means SMS only.",
    )
    val hasAccount: Boolean,
    val createdAt: Instant,
) {
    companion object {
        fun from(contact: EmergencyContact, hasAccount: Boolean) = ContactResponse(
            id = contact.id,
            name = contact.name,
            phone = contact.phone,
            relationship = contact.relationship,
            priority = contact.priority,
            notificationEnabled = contact.notificationEnabled,
            language = contact.language,
            hasAccount = hasAccount,
            createdAt = contact.createdAt,
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════════
// Location
// ══════════════════════════════════════════════════════════════════════════════════

/**
 * A position, always carrying its own age.
 *
 * [ageSeconds] and [stale] exist because a map pin with no timestamp is actively misleading: it
 * looks like where somebody is when it may be where they were twenty minutes ago. The client
 * should never have to work that out from a raw timestamp.
 */
data class LocationResponse(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double?,
    val recordedAt: Instant,
    val ageSeconds: Long,
    @Schema(
        description = "True when the fix is old enough that it should be shown as a last known " +
            "position rather than a current one. A flag rather than a sentence: the client " +
            "words this in the reader's language, since the server only localises what it " +
            "delivers itself.",
    )
    val stale: Boolean,
    @Schema(description = "Opens in any maps app, on any phone, with no SOSync install needed.")
    val mapsLink: String,
) {
    companion object {
        /** Beyond this, a position is described as last known rather than current. */
        private const val STALE_AFTER_SECONDS = 120L

        fun from(update: LocationUpdate): LocationResponse = of(
            update.latitude, update.longitude, update.accuracy, update.recordedAt,
        )

        fun of(
            latitude: Double,
            longitude: Double,
            accuracy: Double?,
            recordedAt: Instant,
        ): LocationResponse {
            val age = Duration.between(recordedAt, Instant.now()).seconds.coerceAtLeast(0)
            return LocationResponse(
                latitude = latitude,
                longitude = longitude,
                accuracy = accuracy,
                recordedAt = recordedAt,
                ageSeconds = age,
                stale = age > STALE_AFTER_SECONDS,
                mapsLink = Geo.mapsLink(latitude, longitude),
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════════
// Incidents
// ══════════════════════════════════════════════════════════════════════════════════

data class ResponderSummary(
    val id: String,
    val organization: String,
    val fullName: String?,
    val phone: String?,
    val verificationStatus: ResponderVerificationStatus,
    val availabilityStatus: ResponderAvailability,
    @Schema(description = "Straight-line distance to the incident in km. Not driving distance.")
    val distanceKm: Double? = null,
) {
    companion object {
        fun from(
            responder: Responder,
            account: User?,
            distanceKm: Double? = null,
        ) = ResponderSummary(
            id = responder.id,
            organization = responder.organization,
            fullName = account?.fullName,
            phone = account?.phone,
            verificationStatus = responder.verificationStatus,
            availabilityStatus = responder.availabilityStatus,
            distanceKm = distanceKm,
        )
    }
}

data class TimelineEntryResponse(
    val id: String,
    val eventType: IncidentEventType,
    val actorLabel: String?,
    val notes: String?,
    val occurredAt: Instant,
) {
    companion object {
        fun from(event: IncidentEvent) = TimelineEntryResponse(
            id = event.id,
            eventType = event.eventType,
            actorLabel = event.actorLabel,
            notes = event.notes,
            occurredAt = event.occurredAt,
        )
    }
}

/**
 * The full view of one emergency.
 *
 * What a caller sees depends on [viewerRelationship]: [location] and [locationTrail] are
 * omitted entirely when [canViewLocation] is false, rather than being returned blanked out.
 * There is no version of this payload where a closed incident leaks a live position to a
 * contact who is no longer entitled to it.
 */
data class IncidentResponse(
    val id: String,
    val status: IncidentStatus,
    val silent: Boolean,
    val note: String?,
    val reporter: PersonSummary,
    val assignedResponder: ResponderSummary?,

    val location: LocationResponse?,
    @Schema(description = "Full trail, present only when explicitly requested and permitted.")
    val locationTrail: List<LocationResponse>? = null,
    val locationPointCount: Long = 0,

    val triggeredAt: Instant,
    val acceptedAt: Instant?,
    val arrivedAt: Instant?,
    val closedAt: Instant?,
    val cancelReason: String?,
    val resolutionNote: String?,

    @Schema(description = "How long the emergency has been open, or how long it ran before closing.")
    val elapsedSeconds: Long,

    val viewerRelationship: ViewerRelationship,
    @Schema(description = "False once a closed incident stops sharing location with this viewer.")
    val canViewLocation: Boolean,

    val timeline: List<TimelineEntryResponse>? = null,
    @Schema(description = "Who was alerted, on which channel, and whether it got through.")
    val notifications: List<NotificationResponse>? = null,
) {
    companion object {
        fun from(
            incident: Incident,
            reporter: User,
            relationship: ViewerRelationship,
            canViewLocation: Boolean,
            assignedResponder: ResponderSummary? = null,
            locationPointCount: Long = 0,
            locationTrail: List<LocationResponse>? = null,
            timeline: List<TimelineEntryResponse>? = null,
            notifications: List<NotificationResponse>? = null,
        ): IncidentResponse {
            val latest = if (canViewLocation &&
                Geo.isPlausible(incident.lastLat, incident.lastLng) &&
                incident.lastLocationAt != null
            ) {
                LocationResponse.of(
                    incident.lastLat!!,
                    incident.lastLng!!,
                    null,
                    incident.lastLocationAt!!,
                )
            } else {
                null
            }

            val ended = incident.closedAt ?: Instant.now()

            return IncidentResponse(
                id = incident.id,
                status = incident.status,
                silent = incident.silent,
                note = incident.note,
                reporter = PersonSummary.from(reporter),
                assignedResponder = assignedResponder,
                location = latest,
                locationTrail = if (canViewLocation) locationTrail else null,
                locationPointCount = locationPointCount,
                triggeredAt = incident.triggeredAt,
                acceptedAt = incident.acceptedAt,
                arrivedAt = incident.arrivedAt,
                closedAt = incident.closedAt,
                cancelReason = incident.cancelReason,
                resolutionNote = incident.resolutionNote,
                elapsedSeconds = Duration.between(incident.triggeredAt, ended)
                    .seconds
                    .coerceAtLeast(0),
                viewerRelationship = relationship,
                canViewLocation = canViewLocation,
                timeline = timeline,
                notifications = notifications,
            )
        }
    }
}

/** What comes back from pressing the button. */
data class TriggerSosResponse(
    val incident: IncidentResponse,
    @Schema(
        description = "True when an emergency was already running and this press joined it " +
            "instead of starting a second one.",
    )
    val alreadyActive: Boolean,
    @Schema(description = "Everyone reached by this alert, and on what channel.")
    val notifications: List<NotificationResponse>,
    @Schema(description = "Plain-language summary the app can show the reporter immediately.")
    val summary: String,
)

/** An unclaimed or assigned alert as it appears in a responder list. */
data class ResponderAlertResponse(
    val incidentId: String,
    val status: IncidentStatus,
    val reporter: PersonSummary,
    val location: LocationResponse?,
    val distanceKm: Double?,
    val triggeredAt: Instant,
    val waitingSeconds: Long,
    val note: String?,
    val isMine: Boolean,
)

// ══════════════════════════════════════════════════════════════════════════════════
// Notifications
// ══════════════════════════════════════════════════════════════════════════════════

data class NotificationResponse(
    val id: String,
    val incidentId: String?,
    val type: NotificationType,
    val channel: NotificationChannel,
    val deliveryStatus: DeliveryStatus,
    val recipientLabel: String?,
    val recipientPhone: String?,
    val title: String,
    val body: String,
    @Schema(description = "The language this delivery actually went out in.")
    val language: Language,
    val failureReason: String?,
    val acknowledgedAt: Instant?,
    val createdAt: Instant,
    @Schema(
        description = "True when no real gateway was connected and nothing actually left the " +
            "building. Surfaced rather than hidden: a safety tool that overstates delivery is " +
            "worse than one that admits it.",
    )
    val simulated: Boolean,
) {
    companion object {
        fun from(record: NotificationRecord) = NotificationResponse(
            id = record.id,
            incidentId = record.incidentId,
            type = record.type,
            channel = record.channel,
            deliveryStatus = record.deliveryStatus,
            recipientLabel = record.recipientLabel,
            recipientPhone = record.recipientPhone,
            title = record.title,
            body = record.body,
            language = record.language,
            failureReason = record.failureReason,
            acknowledgedAt = record.acknowledgedAt,
            createdAt = record.createdAt,
            simulated = record.deliveryStatus == DeliveryStatus.SIMULATED,
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════════
// Family and plans
// ══════════════════════════════════════════════════════════════════════════════════

data class FamilyMemberResponse(
    val userId: String,
    val fullName: String,
    val phone: String,
    val relationship: String?,
    val isOwner: Boolean,
)

data class FamilyResponse(
    val id: String,
    val name: String,
    val ownerUserId: String,
    val members: List<FamilyMemberResponse>,
    val createdAt: Instant,
)

data class PlanResponse(
    val planType: PlanType,
    val name: String,
    val priceMonthly: String,
    val features: List<String>,
)

data class SubscriptionResponse(
    val id: String,
    val planType: PlanType,
    val status: SubscriptionStatus,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    @Schema(description = "Always true in the prototype. No payment was taken.")
    val simulated: Boolean,
) {
    companion object {
        fun from(subscription: Subscription) = SubscriptionResponse(
            id = subscription.id,
            planType = subscription.planType,
            status = subscription.status,
            startDate = subscription.startDate,
            endDate = subscription.endDate,
            simulated = subscription.simulated,
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════════
// Administration
// ══════════════════════════════════════════════════════════════════════════════════

data class AdminStatsResponse(
    val totalUsers: Long,
    val totalResponders: Long,
    val verifiedResponders: Long,
    val pendingResponders: Long,
    val activeIncidents: Long,
    val incidentsToday: Long,
    val resolvedIncidents: Long,
    val cancelledIncidents: Long,
    @Schema(description = "Median seconds from SOS to a responder accepting, over closed incidents.")
    val medianSecondsToAccept: Long?,
)

data class ResponderAdminResponse(
    val id: String,
    val userId: String,
    val fullName: String,
    val phone: String,
    val organization: String,
    val verificationStatus: ResponderVerificationStatus,
    val availabilityStatus: ResponderAvailability,
    val lastKnownLocation: LocationResponse?,
    val verifiedAt: Instant?,
    val createdAt: Instant,
)

/** Simple envelope for list endpoints, so clients do not have to special-case Spring page JSON. */
data class PagedResponse<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
    val totalPages: Int,
) {
    companion object {
        fun <E : Any, T> of(page: org.springframework.data.domain.Page<E>, map: (E) -> T) =
            PagedResponse(
                items = page.content.map(map),
                page = page.number,
                size = page.size,
                totalItems = page.totalElements,
                totalPages = page.totalPages,
            )
    }
}

data class MessageResponse(val message: String)
