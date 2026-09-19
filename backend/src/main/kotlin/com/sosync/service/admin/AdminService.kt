package com.sosync.service.admin

import com.sosync.common.ConflictException
import com.sosync.common.NotFoundException
import com.sosync.domain.AccountStatus
import com.sosync.domain.IncidentStatus
import com.sosync.domain.ResponderVerificationStatus
import com.sosync.domain.Role
import com.sosync.persistence.IncidentRepository
import com.sosync.persistence.ResponderRepository
import com.sosync.persistence.UserRepository
import com.sosync.web.dto.AdminStatsResponse
import com.sosync.web.dto.UserResponse
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The operator view: who is on the platform, which responders are trusted, and what is
 * happening right now.
 *
 * Note what an admin cannot do from here. There is no endpoint to cancel somebody else's
 * emergency, and none to read a location outside the normal incident view, which is audited
 * like any other. Administration covers the trust layer, not the emergencies themselves.
 */
@Service
class AdminService(
    private val userRepository: UserRepository,
    private val responderRepository: ResponderRepository,
    private val incidentRepository: IncidentRepository,
    private val auditService: AuditService,
) {

    @Transactional(readOnly = true)
    fun stats(): AdminStatsResponse {
        val since = Instant.now().minus(1, ChronoUnit.DAYS)

        return AdminStatsResponse(
            totalUsers = userRepository.countByRole(Role.USER),
            totalResponders = userRepository.countByRole(Role.RESPONDER),
            verifiedResponders = responderRepository
                .countByVerificationStatus(ResponderVerificationStatus.VERIFIED),
            pendingResponders = responderRepository
                .countByVerificationStatus(ResponderVerificationStatus.PENDING),
            activeIncidents = incidentRepository.countByStatusIn(IncidentStatus.OPEN),
            incidentsToday = incidentRepository.countByTriggeredAtAfter(since),
            resolvedIncidents = incidentRepository.countByStatus(IncidentStatus.RESOLVED),
            cancelledIncidents = incidentRepository.countByStatus(IncidentStatus.CANCELLED),
            medianSecondsToAccept = medianSecondsToAccept(),
        )
    }

    @Transactional(readOnly = true)
    fun users(role: Role?, page: Int, size: Int): Page<UserResponse> {
        val pageable = PageRequest.of(page, size)
        val users = if (role != null) {
            userRepository.findAllByRole(role, pageable)
        } else {
            userRepository.findAll(pageable)
        }
        return users.map { UserResponse.from(it) }
    }

    /**
     * Suspend or restore an account.
     *
     * Refuses to suspend an account with an emergency in progress. Cutting off somebody who is
     * mid-incident is the one moment when an administrative action could do real harm, so it is
     * blocked rather than warned about.
     */
    @Transactional
    fun setUserStatus(userId: String, status: AccountStatus, adminId: String): UserResponse {
        val user = userRepository.findById(userId).orElseThrow {
            NotFoundException("No such user")
        }

        if (status == AccountStatus.SUSPENDED) {
            incidentRepository
                .findFirstByUserIdAndStatusInOrderByTriggeredAtDesc(userId, IncidentStatus.OPEN)
                ?.let {
                    throw ConflictException(
                        "That account has an emergency in progress (incident ${it.id}). " +
                            "Wait until it closes.",
                    )
                }
        }

        val previous = user.status
        user.status = status
        user.updatedAt = Instant.now()
        val saved = userRepository.save(user)

        auditService.record(
            actorId = adminId,
            action = AuditService.USER_STATUS_CHANGED,
            targetType = "user",
            targetId = userId,
            detail = "$previous -> $status",
        )

        return UserResponse.from(saved)
    }

    /**
     * Median seconds from SOS to a responder accepting.
     *
     * The one number that says whether any of this works. Median rather than mean because a
     * single incident nobody picked up for an hour would otherwise hide a hundred good ones.
     *
     * Computed in memory over accepted incidents. Fine at prototype volume; a real deployment
     * would want this as a SQL aggregate or a rolled-up metric.
     */
    private fun medianSecondsToAccept(): Long? {
        val durations = incidentRepository.findAll()
            .mapNotNull { incident ->
                incident.acceptedAt?.let {
                    Duration.between(incident.triggeredAt, it).seconds
                }
            }
            .sorted()

        if (durations.isEmpty()) return null

        val middle = durations.size / 2
        return if (durations.size % 2 == 0) {
            (durations[middle - 1] + durations[middle]) / 2
        } else {
            durations[middle]
        }
    }
}
