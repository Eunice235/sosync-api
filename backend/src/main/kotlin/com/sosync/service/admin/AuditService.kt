package com.sosync.service.admin

import com.sosync.domain.AuditEntry
import com.sosync.persistence.AuditRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Append-only record of the actions worth being able to answer for later.
 *
 * The question this exists to answer is "who saw where I was". Location is the sensitive
 * capability in SOSync, so a responder or admin opening a live position is recorded the same
 * way a state change is.
 */
@Service
class AuditService(private val auditRepository: AuditRepository) {

    /**
     * Record an action, in the caller's transaction.
     *
     * Joining the caller deliberately: an audit row saying a state change happened must not
     * survive that change being rolled back.
     */
    @Transactional
    fun record(
        actorId: String?,
        action: String,
        targetType: String? = null,
        targetId: String? = null,
        detail: String? = null,
    ) {
        auditRepository.save(entry(actorId, action, targetType, targetId, detail))
    }

    /**
     * Record an attempt that was **refused**, in its own transaction.
     *
     * This exists because the obvious version does not work. A rejected operation throws, the
     * caller's transaction rolls back, and an audit row written inside it disappears with
     * everything else. The refusals are exactly the entries worth keeping: a failed safety-PIN
     * attempt is the trace a stolen phone leaves, and it is worthless if it vanishes at the
     * moment it is created.
     *
     * REQUIRES_NEW suspends the caller's transaction and commits this row independently, so it
     * outlives the rollback.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordRefusal(
        actorId: String?,
        action: String,
        targetType: String? = null,
        targetId: String? = null,
        detail: String? = null,
    ) {
        auditRepository.save(entry(actorId, action, targetType, targetId, detail))
    }

    private fun entry(
        actorId: String?,
        action: String,
        targetType: String?,
        targetId: String?,
        detail: String?,
    ) = AuditEntry(
        actorId = actorId,
        action = action,
        targetType = targetType,
        targetId = targetId,
        detail = detail?.take(500),
    )

    @Transactional(readOnly = true)
    fun forTarget(targetType: String, targetId: String): List<AuditEntry> =
        auditRepository.findAllByTargetTypeAndTargetIdOrderByOccurredAtDesc(targetType, targetId)

    companion object {
        const val INCIDENT_TRIGGERED = "incident.triggered"
        const val INCIDENT_CANCELLED = "incident.cancelled"
        const val INCIDENT_ACCEPTED = "incident.accepted"
        const val INCIDENT_STATUS_CHANGED = "incident.status_changed"
        const val LOCATION_VIEWED = "location.viewed"
        const val LOCATION_REPORTED = "location.reported"
        const val RESPONDER_VERIFIED = "responder.verification_changed"
        const val USER_STATUS_CHANGED = "user.status_changed"
        const val SAFETY_PIN_SET = "user.safety_pin_set"
    }
}
