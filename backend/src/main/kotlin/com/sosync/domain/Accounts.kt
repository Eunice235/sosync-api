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
 * An account. One table for all three roles: the difference between a user, a responder and an
 * admin is the [role] column plus, for responders, a row in `responders`.
 *
 * Entities hold foreign keys as plain id strings rather than JPA associations. It keeps reads
 * explicit, keeps lazy-loading surprises out of the emergency path, and matches how the Readers
 * services key on ids across boundaries.
 */
@Entity
@Table(name = "users")
class User(
    @Id
    @Column(length = 21)
    var id: String = NanoIds.generate(),

    @Column(name = "full_name", nullable = false, length = 120)
    var fullName: String,

    /** Normalised to E.164 on write. This is the login identifier and the SMS address. */
    @Column(nullable = false, length = 20)
    var phone: String,

    @Column(length = 160)
    var email: String? = null,

    @Column(name = "password_hash", nullable = false, length = 100)
    var passwordHash: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var role: Role = Role.USER,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var status: AccountStatus = AccountStatus.ACTIVE,

    /**
     * BCrypt hash of the safety PIN required to cancel an active incident. Null means the user
     * has not set one, in which case cancellation falls back to the account password.
     */
    @Column(name = "safety_pin_hash", length = 100)
    var safetyPinHash: String? = null,

    /** Free text a responder sees: medical notes, what the user looks like, who to expect. */
    @Column(name = "emergency_note", length = 500)
    var emergencyNote: String? = null,

    @Column(name = "photo_url", length = 500)
    var photoUrl: String? = null,

    @Column(name = "phone_verified", nullable = false)
    var phoneVerified: Boolean = false,

    /**
     * The language this person reads alerts in.
     *
     * English only at present. Held on the recipient rather than on the deployment because the
     * people caught up in one emergency do not necessarily share a language, and that is the
     * shape a second language would need - so the column stays even while there is one value
     * in it.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "preferred_language", nullable = false, length = 8)
    var preferredLanguage: Language = Language.DEFAULT,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)

@Entity
@Table(name = "verification_codes")
class VerificationCode(
    @Id
    @Column(length = 21)
    var id: String = NanoIds.generate(),

    @Column(name = "user_id", nullable = false, length = 21)
    var userId: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var purpose: VerificationPurpose,

    @Column(nullable = false, length = 10)
    var code: String,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,

    @Column(name = "consumed_at")
    var consumedAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
) {
    fun isUsable(now: Instant = Instant.now()): Boolean =
        consumedAt == null && expiresAt.isAfter(now)
}

/**
 * A nominated trusted contact. Stored by phone number so it works for someone who has never
 * installed the app; if that number belongs to a registered account, the alert also arrives
 * in-app.
 */
@Entity
@Table(name = "emergency_contacts")
class EmergencyContact(
    @Id
    @Column(length = 21)
    var id: String = NanoIds.generate(),

    @Column(name = "user_id", nullable = false, length = 21)
    var userId: String,

    @Column(nullable = false, length = 120)
    var name: String,

    @Column(nullable = false, length = 20)
    var phone: String,

    @Column(length = 60)
    var relationship: String? = null,

    /** 1 is highest. Priority 1 contacts are the ones guaranteed an SMS. */
    @Column(nullable = false)
    var priority: Int = 1,

    @Column(name = "notification_enabled", nullable = false)
    var notificationEnabled: Boolean = true,

    /**
     * Language for this contact specifically.
     *
     * Null means fall back to the language of whoever raised the alert, which is where every
     * contact sits while only English is configured. The field exists because a contact with no
     * account has no profile to read a preference from, and the SMS is the one channel that
     * reaches a basic phone - so when a second language returns, the reporter needs somewhere
     * to say which one that person reads. Where the contact does have an account, their own
     * preference wins over this field.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 8)
    var language: Language? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)

@Entity
@Table(name = "responders")
class Responder(
    @Id
    @Column(length = 21)
    var id: String = NanoIds.generate(),

    @Column(name = "user_id", nullable = false, length = 21)
    var userId: String,

    @Column(nullable = false, length = 160)
    var organization: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 32)
    var verificationStatus: ResponderVerificationStatus = ResponderVerificationStatus.PENDING,

    @Enumerated(EnumType.STRING)
    @Column(name = "availability_status", nullable = false, length = 32)
    var availabilityStatus: ResponderAvailability = ResponderAvailability.OFFLINE,

    @Column(name = "current_lat")
    var currentLat: Double? = null,

    @Column(name = "current_lng")
    var currentLng: Double? = null,

    @Column(name = "location_updated_at")
    var locationUpdatedAt: Instant? = null,

    @Column(name = "verified_at")
    var verifiedAt: Instant? = null,

    @Column(name = "verified_by", length = 21)
    var verifiedBy: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    /**
     * Whether this responder should receive new alerts. Both conditions matter: an unverified
     * responder has not been vetted, and an unavailable one will not turn up.
     */
    val isDispatchable: Boolean
        get() = verificationStatus == ResponderVerificationStatus.VERIFIED &&
            availabilityStatus == ResponderAvailability.AVAILABLE
}

/**
 * Append-only record of sensitive actions. Location access is the capability worth auditing
 * here, so reads of a live position are recorded alongside writes.
 */
@Entity
@Table(name = "audit_log")
class AuditEntry(
    @Id
    @Column(length = 21)
    var id: String = NanoIds.generate(),

    @Column(name = "actor_id", length = 21)
    var actorId: String? = null,

    @Column(nullable = false, length = 60)
    var action: String,

    @Column(name = "target_type", length = 40)
    var targetType: String? = null,

    @Column(name = "target_id", length = 21)
    var targetId: String? = null,

    @Column(length = 500)
    var detail: String? = null,

    @Column(name = "occurred_at", nullable = false)
    var occurredAt: Instant = Instant.now(),
)
