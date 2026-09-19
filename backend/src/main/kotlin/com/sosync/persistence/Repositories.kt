package com.sosync.persistence

import com.sosync.domain.AuditEntry
import com.sosync.domain.EmergencyContact
import com.sosync.domain.Family
import com.sosync.domain.FamilyMember
import com.sosync.domain.Incident
import com.sosync.domain.IncidentEvent
import com.sosync.domain.IncidentStatus
import com.sosync.domain.LocationUpdate
import com.sosync.domain.NotificationRecord
import com.sosync.domain.Responder
import com.sosync.domain.ResponderAvailability
import com.sosync.domain.ResponderVerificationStatus
import com.sosync.domain.Role
import com.sosync.domain.Subscription
import com.sosync.domain.User
import com.sosync.domain.VerificationCode
import com.sosync.domain.VerificationPurpose
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface UserRepository : JpaRepository<User, String> {
    fun findByPhone(phone: String): User?

    @Query("select u from User u where lower(u.email) = lower(:email)")
    fun findByEmailIgnoreCase(@Param("email") email: String): User?

    fun existsByPhone(phone: String): Boolean

    @Query("select count(u) > 0 from User u where lower(u.email) = lower(:email)")
    fun existsByEmailIgnoreCase(@Param("email") email: String): Boolean

    fun findAllByRole(role: Role, pageable: Pageable): Page<User>

    fun countByRole(role: Role): Long

    /** Bulk lookup so a list of incidents or contacts hydrates in one query, never per row. */
    fun findAllByIdIn(ids: Collection<String>): List<User>

    fun findAllByPhoneIn(phones: Collection<String>): List<User>
}

interface VerificationCodeRepository : JpaRepository<VerificationCode, String> {
    fun findFirstByUserIdAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
        userId: String,
        purpose: VerificationPurpose,
    ): VerificationCode?
}

interface EmergencyContactRepository : JpaRepository<EmergencyContact, String> {
    fun findAllByUserIdOrderByPriorityAscCreatedAtAsc(userId: String): List<EmergencyContact>

    fun findAllByUserIdAndNotificationEnabledTrueOrderByPriorityAsc(
        userId: String,
    ): List<EmergencyContact>

    fun findByIdAndUserId(id: String, userId: String): EmergencyContact?

    fun existsByUserIdAndPhone(userId: String, phone: String): Boolean

    fun countByUserId(userId: String): Long

    /**
     * Whose trusted-contact list does this phone number appear on? This is what lets a
     * registered contact see the incidents they are nominated for, without either party having
     * to link accounts.
     */
    fun findAllByPhone(phone: String): List<EmergencyContact>
}

interface ResponderRepository : JpaRepository<Responder, String> {
    fun findByUserId(userId: String): Responder?

    fun findAllByVerificationStatusAndAvailabilityStatus(
        verificationStatus: ResponderVerificationStatus,
        availabilityStatus: ResponderAvailability,
    ): List<Responder>

    fun findAllByVerificationStatus(
        verificationStatus: ResponderVerificationStatus,
        pageable: Pageable,
    ): Page<Responder>

    fun countByVerificationStatus(verificationStatus: ResponderVerificationStatus): Long

    fun findAllByIdIn(ids: Collection<String>): List<Responder>
}

interface IncidentRepository : JpaRepository<Incident, String> {

    /**
     * The caller's currently open incident, if any. A partial unique index in the schema
     * guarantees there is at most one, so a repeated press cannot fan out into several.
     */
    fun findFirstByUserIdAndStatusInOrderByTriggeredAtDesc(
        userId: String,
        statuses: Collection<IncidentStatus>,
    ): Incident?

    fun findAllByUserIdOrderByTriggeredAtDesc(userId: String, pageable: Pageable): Page<Incident>

    fun findAllByStatusInOrderByTriggeredAtDesc(
        statuses: Collection<IncidentStatus>,
        pageable: Pageable,
    ): Page<Incident>

    fun findAllByAssignedResponderIdOrderByTriggeredAtDesc(
        responderId: String,
        pageable: Pageable,
    ): Page<Incident>

    /** Unclaimed alerts, oldest first: the one waiting longest needs somebody the most. */
    fun findAllByStatusOrderByTriggeredAtAsc(status: IncidentStatus): List<Incident>

    fun findAllByUserIdInAndStatusInOrderByTriggeredAtDesc(
        userIds: Collection<String>,
        statuses: Collection<IncidentStatus>,
    ): List<Incident>

    fun countByStatus(status: IncidentStatus): Long

    fun countByStatusIn(statuses: Collection<IncidentStatus>): Long

    fun countByTriggeredAtAfter(since: Instant): Long
}

interface LocationUpdateRepository : JpaRepository<LocationUpdate, String> {
    fun findAllByIncidentIdOrderByRecordedAtAsc(incidentId: String): List<LocationUpdate>

    fun findFirstByIncidentIdOrderByRecordedAtDesc(incidentId: String): LocationUpdate?

    fun countByIncidentId(incidentId: String): Long
}

interface IncidentEventRepository : JpaRepository<IncidentEvent, String> {
    fun findAllByIncidentIdOrderByOccurredAtAsc(incidentId: String): List<IncidentEvent>
}

interface NotificationRepository : JpaRepository<NotificationRecord, String> {
    fun findAllByRecipientUserIdOrderByCreatedAtDesc(
        recipientUserId: String,
        pageable: Pageable,
    ): Page<NotificationRecord>

    fun findAllByIncidentIdOrderByCreatedAtAsc(incidentId: String): List<NotificationRecord>

    fun countByRecipientUserIdAndAcknowledgedAtIsNull(recipientUserId: String): Long

    fun findByIdAndRecipientUserId(id: String, recipientUserId: String): NotificationRecord?
}

interface FamilyRepository : JpaRepository<Family, String> {
    fun findByOwnerUserId(ownerUserId: String): Family?
}

interface FamilyMemberRepository : JpaRepository<FamilyMember, String> {
    fun findAllByFamilyId(familyId: String): List<FamilyMember>

    fun findAllByUserId(userId: String): List<FamilyMember>

    fun findByFamilyIdAndUserId(familyId: String, userId: String): FamilyMember?

    fun existsByFamilyIdAndUserId(familyId: String, userId: String): Boolean
}

interface SubscriptionRepository : JpaRepository<Subscription, String> {
    fun findByOwnerUserId(ownerUserId: String): Subscription?
}

interface AuditRepository : JpaRepository<AuditEntry, String> {
    fun findAllByTargetTypeAndTargetIdOrderByOccurredAtDesc(
        targetType: String,
        targetId: String,
    ): List<AuditEntry>
}
