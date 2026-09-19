package com.sosync.service.notification

import com.sosync.common.NotFoundException
import com.sosync.persistence.IncidentRepository
import com.sosync.persistence.NotificationRepository
import com.sosync.persistence.UserRepository
import com.sosync.service.incident.IncidentService
import com.sosync.web.dto.NotificationResponse
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * The recipient side of notifications: the in-app tray, and acknowledgement.
 */
@Service
class NotificationService(
    private val notificationRepository: NotificationRepository,
    private val incidentRepository: IncidentRepository,
    private val userRepository: UserRepository,
    private val incidentService: IncidentService,
) {

    @Transactional(readOnly = true)
    fun tray(userId: String, page: Int, size: Int): Page<NotificationResponse> =
        notificationRepository
            .findAllByRecipientUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size))
            .map { NotificationResponse.from(it) }

    @Transactional(readOnly = true)
    fun unacknowledgedCount(userId: String): Long =
        notificationRepository.countByRecipientUserIdAndAcknowledgedAtIsNull(userId)

    /**
     * Mark an alert as seen.
     *
     * Acknowledgement is written to the incident timeline as well as the notification row,
     * because it changes something the reporter genuinely needs to know: not "an alert was
     * sent" but "somebody who can help has seen it".
     */
    @Transactional
    fun acknowledge(notificationId: String, userId: String): NotificationResponse {
        val record = notificationRepository.findByIdAndRecipientUserId(notificationId, userId)
            ?: throw NotFoundException("No such notification")

        if (record.acknowledgedAt != null) {
            return NotificationResponse.from(record)
        }

        record.acknowledgedAt = Instant.now()
        val saved = notificationRepository.save(record)

        record.incidentId
            ?.let { incidentRepository.findById(it).orElse(null) }
            ?.takeIf { it.isOpen }
            ?.let { incident ->
                val actor = userRepository.findById(userId).orElse(null)
                if (actor != null) {
                    incidentService.acknowledgeOnTimeline(
                        incident,
                        actor,
                        record.recipientLabel,
                    )
                }
            }

        return NotificationResponse.from(saved)
    }
}
