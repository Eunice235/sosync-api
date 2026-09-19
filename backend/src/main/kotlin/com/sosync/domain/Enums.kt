package com.sosync.domain

/**
 * Every enum here is persisted by name, never by ordinal. Reordering an ordinal-mapped enum
 * silently rewrites the meaning of rows already in the database.
 */

enum class Role {
    /** Ordinary account: triggers SOS, keeps trusted contacts. */
    USER,

    /** Verified guard or community responder. Sees alerts and can accept an incident. */
    RESPONDER,

    /** Platform operator. Verifies responders and reviews incidents. */
    ADMIN,
    ;

    /** Spring Security expects the `ROLE_` prefix on authorities it matches with `hasRole`. */
    val authority: String get() = "ROLE_$name"
}

enum class AccountStatus { ACTIVE, SUSPENDED }

/**
 * A language an alert can be delivered in.
 *
 * English only, by product decision. The mechanism around it is kept rather than torn out: the
 * language of each delivery is still recorded, and alerts are still rendered through a
 * catalogue keyed on this enum. Adding a language is therefore adding a constant here plus an
 * implementation of `AlertStrings` - at which point the compiler names every message still
 * missing - rather than rebuilding the path that carries it.
 *
 * Kept as an enum with one constant, not collapsed away, because the columns that store it
 * already exist and a single-valued enum is a clearer statement of intent than a hard-coded
 * string in nine places.
 */
enum class Language(
    /** ISO 639-1, which is what clients send in `Accept-Language` and store in settings. */
    val code: String,
    val englishName: String,
    val nativeName: String,
) {
    EN("en", "English", "English"),
    ;

    companion object {
        val DEFAULT = EN

        /** Accepts "en", "EN" or "en-KE"; returns null for anything unrecognised. */
        fun fromCode(code: String?): Language? {
            val head = code?.trim()?.take(2)?.lowercase() ?: return null
            return entries.firstOrNull { it.code == head }
        }

        fun fromCodeOrDefault(code: String?): Language = fromCode(code) ?: DEFAULT
    }
}

enum class VerificationPurpose { PHONE_VERIFICATION, PASSWORD_RESET }

/**
 * Incident lifecycle.
 *
 * ```
 * TRIGGERED ──> ACCEPTED ──> RESPONDING ──> ARRIVED ──> RESOLVED
 *     │             │             │             │
 *     └─────────────┴─────────────┴─────────────┴──> CANCELLED
 * ```
 *
 * Forward-only while open. A resolved or cancelled incident is terminal: reopening would make
 * the timeline unreadable, and the correct action is a new incident.
 */
enum class IncidentStatus {
    /** SOS raised. Contacts and responders alerted; nobody has taken it yet. */
    TRIGGERED,

    /** A verified responder has taken ownership. */
    ACCEPTED,

    /** The responder is on the way. */
    RESPONDING,

    /** The responder has reached the location. */
    ARRIVED,

    /** Help was given, or the situation ended. Terminal. */
    RESOLVED,

    /** Stood down by the reporter with their safety PIN. Terminal. */
    CANCELLED,
    ;

    val isOpen: Boolean get() = this !in TERMINAL

    companion object {
        val TERMINAL = setOf(RESOLVED, CANCELLED)

        /** The statuses in which live location sharing is permitted. */
        val OPEN = entries.filter { it.isOpen }.toSet()
    }
}

enum class ResponderVerificationStatus { PENDING, VERIFIED, REJECTED, DISABLED }

enum class ResponderAvailability { AVAILABLE, BUSY, OFFLINE }

enum class IncidentEventType {
    TRIGGERED,
    NOTIFIED,
    ACKNOWLEDGED,
    LOCATION_UPDATED,
    ACCEPTED,
    RESPONDING,
    ARRIVED,
    RESOLVED,
    CANCELLED,
}

enum class NotificationType {
    SOS_TRIGGERED,
    RESPONDER_ACCEPTED,
    RESPONDER_RESPONDING,
    RESPONDER_ARRIVED,
    INCIDENT_RESOLVED,
    INCIDENT_CANCELLED,
}

enum class NotificationChannel {
    /** Push to a registered device. */
    PUSH,

    /** The fallback that works on a feature phone and without mobile data. */
    SMS,

    /** Tray entry inside the app. Always written, so nothing is lost if push fails. */
    IN_APP,
}

enum class DeliveryStatus {
    PENDING,
    SENT,
    FAILED,

    /**
     * No real gateway was configured, so nothing left the building. Recorded explicitly rather
     * than reported as SENT: a safety system that overstates delivery is worse than one that
     * admits it.
     */
    SIMULATED,
}

enum class PlanType { INDIVIDUAL, FAMILY }

enum class SubscriptionStatus { TRIAL, ACTIVE, EXPIRED, CANCELLED }
