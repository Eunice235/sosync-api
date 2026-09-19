package com.sosync.domain

import com.sosync.common.NanoIds
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * One emergency. Created the moment the SOS hold completes and never deleted: the record of a
 * cancelled false alarm is itself worth keeping.
 *
 * The latest position is denormalised onto the row ([lastLat], [lastLng], [lastLocationAt])
 * because it is read on every poll by every watcher, while the full trail in `location_updates`
 * is read only when someone opens the map.
 */
@Entity
@Table(name = "incidents")
class Incident(
    @Id
    @Column(length = 21)
    var id: String = NanoIds.generate(),

    @Column(name = "user_id", nullable = false, length = 21)
    var userId: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var status: IncidentStatus = IncidentStatus.TRIGGERED,

    /** When true the reporting device gives no visible or audible sign. Default for a reason. */
    @Column(nullable = false)
    var silent: Boolean = true,

    @Column(length = 500)
    var note: String? = null,

    @Column(name = "trigger_lat")
    var triggerLat: Double? = null,

    @Column(name = "trigger_lng")
    var triggerLng: Double? = null,

    @Column(name = "trigger_accuracy")
    var triggerAccuracy: Double? = null,

    @Column(name = "last_lat")
    var lastLat: Double? = null,

    @Column(name = "last_lng")
    var lastLng: Double? = null,

    @Column(name = "last_location_at")
    var lastLocationAt: Instant? = null,

    @Column(name = "assigned_responder_id", length = 21)
    var assignedResponderId: String? = null,

    @Column(name = "cancel_reason", length = 200)
    var cancelReason: String? = null,

    @Column(name = "resolution_note", length = 500)
    var resolutionNote: String? = null,

    @Column(name = "triggered_at", nullable = false)
    var triggeredAt: Instant = Instant.now(),

    @Column(name = "accepted_at")
    var acceptedAt: Instant? = null,

    @Column(name = "arrived_at")
    var arrivedAt: Instant? = null,

    /** Set when the incident reaches RESOLVED or CANCELLED. Live location stops at this point. */
    @Column(name = "closed_at")
    var closedAt: Instant? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    val isOpen: Boolean get() = status.isOpen
}

/**
 * A single GPS fix inside an incident. [recordedAt] is the device timestamp, not the server
 * one, so a fix taken in a dead spot keeps its true time when the phone finally uploads it.
 */
@Entity
@Table(name = "location_updates")
class LocationUpdate(
    @Id
    @Column(length = 21)
    var id: String = NanoIds.generate(),

    @Column(name = "incident_id", nullable = false, length = 21)
    var incidentId: String,

    @Column(nullable = false)
    var latitude: Double,

    @Column(nullable = false)
    var longitude: Double,

    /** Metres of uncertainty as reported by the device, when it reports any. */
    @Column
    var accuracy: Double? = null,

    @Column(name = "recorded_at", nullable = false)
    var recordedAt: Instant = Instant.now(),

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)

/**
 * One entry in the incident timeline. Append-only: statuses change, but the history of how they
 * changed does not.
 */
@Entity
@Table(name = "incident_events")
class IncidentEvent(
    @Id
    @Column(length = 21)
    var id: String = NanoIds.generate(),

    @Column(name = "incident_id", nullable = false, length = 21)
    var incidentId: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    var eventType: IncidentEventType,

    @Column(name = "actor_id", length = 21)
    var actorId: String? = null,

    /** Human-readable actor, captured at write time so the timeline reads without extra joins. */
    @Column(name = "actor_label", length = 120)
    var actorLabel: String? = null,

    @Column(length = 500)
    var notes: String? = null,

    @Column(name = "occurred_at", nullable = false)
    var occurredAt: Instant = Instant.now(),
)

/**
 * One delivery attempt to one recipient. Written for every channel including IN_APP, so the
 * tray is the same data the SMS and push gateways were handed.
 */
@Entity
@Table(name = "notifications")
class NotificationRecord(
    @Id
    @Column(length = 21)
    var id: String = NanoIds.generate(),

    @Column(name = "incident_id", length = 21)
    var incidentId: String? = null,

    /** Null when the recipient is a phone number with no SOSync account. */
    @Column(name = "recipient_user_id", length = 21)
    var recipientUserId: String? = null,

    @Column(name = "recipient_phone", length = 20)
    var recipientPhone: String? = null,

    @Column(name = "recipient_label", length = 120)
    var recipientLabel: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    var type: NotificationType,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var channel: NotificationChannel,

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", nullable = false, length = 20)
    var deliveryStatus: DeliveryStatus = DeliveryStatus.PENDING,

    @Column(nullable = false, length = 160)
    var title: String,

    @Column(nullable = false, length = 600)
    var body: String,

    /** The language this delivery went out in, recorded rather than inferred after the fact. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    var language: Language = Language.DEFAULT,

    @Column(name = "failure_reason", length = 200)
    var failureReason: String? = null,

    @Column(name = "acknowledged_at")
    var acknowledgedAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)
