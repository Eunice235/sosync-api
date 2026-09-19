package com.sosync.service.responder

import com.sosync.common.BadRequestException
import com.sosync.common.Geo
import com.sosync.common.NotFoundException
import com.sosync.domain.Responder
import com.sosync.domain.ResponderAvailability
import com.sosync.domain.ResponderVerificationStatus
import com.sosync.persistence.ResponderRepository
import com.sosync.persistence.UserRepository
import com.sosync.web.dto.LocationResponse
import com.sosync.web.dto.ResponderAdminResponse
import com.sosync.web.dto.ResponderSummary
import com.sosync.service.admin.AuditService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

private val log = KotlinLogging.logger {}

@Service
class ResponderService(
    private val responderRepository: ResponderRepository,
    private val userRepository: UserRepository,
    private val auditService: AuditService,
) {

    @Transactional(readOnly = true)
    fun profileFor(userId: String): ResponderSummary {
        val responder = requireResponder(userId)
        return ResponderSummary.from(responder, userRepository.findById(userId).orElse(null))
    }

    /**
     * Go on or off shift.
     *
     * A responder who marks themselves AVAILABLE starts receiving alerts; OFFLINE stops them.
     * Verification is not affected either way: an unverified responder can set themselves
     * available all day and still be sent nothing.
     */
    @Transactional
    fun setAvailability(userId: String, availability: ResponderAvailability): ResponderSummary {
        val responder = requireResponder(userId)
        responder.availabilityStatus = availability
        responder.updatedAt = Instant.now()

        // Coming on shift without a position means no alert can be matched by distance, so the
        // client is expected to post a location immediately afterwards.
        if (availability == ResponderAvailability.AVAILABLE && responder.locationUpdatedAt == null) {
            log.info { "Responder ${responder.id} is available but has no known position yet" }
        }

        val saved = responderRepository.save(responder)
        return ResponderSummary.from(saved, userRepository.findById(userId).orElse(null))
    }

    /**
     * Report where the responder is.
     *
     * This is a position stored outside any incident, which makes it the one piece of standing
     * location data in the system. It is justified by what it buys: matching an emergency to
     * whoever is genuinely close. It is also why responder positions go stale and stop being
     * used, rather than being kept indefinitely.
     */
    @Transactional
    fun updateLocation(userId: String, latitude: Double, longitude: Double): ResponderSummary {
        if (!Geo.isPlausible(latitude, longitude)) {
            throw BadRequestException("Those coordinates are not a usable position")
        }

        val responder = requireResponder(userId)
        responder.currentLat = latitude
        responder.currentLng = longitude
        responder.locationUpdatedAt = Instant.now()
        responder.updatedAt = Instant.now()

        val saved = responderRepository.save(responder)
        return ResponderSummary.from(saved, userRepository.findById(userId).orElse(null))
    }

    // ── Administration ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    fun listForAdmin(
        status: ResponderVerificationStatus?,
        page: Int,
        size: Int,
    ): Page<ResponderAdminResponse> {
        val pageable = PageRequest.of(page, size)
        val responders = if (status != null) {
            responderRepository.findAllByVerificationStatus(status, pageable)
        } else {
            responderRepository.findAll(pageable)
        }

        val accounts = userRepository
            .findAllByIdIn(responders.content.map { it.userId })
            .associateBy { it.id }

        return responders.map { responder ->
            val account = accounts[responder.userId]
            ResponderAdminResponse(
                id = responder.id,
                userId = responder.userId,
                fullName = account?.fullName ?: "Unknown",
                phone = account?.phone ?: "",
                organization = responder.organization,
                verificationStatus = responder.verificationStatus,
                availabilityStatus = responder.availabilityStatus,
                lastKnownLocation = responder.locationUpdatedAt
                    ?.takeIf { Geo.isPlausible(responder.currentLat, responder.currentLng) }
                    ?.let {
                        LocationResponse.of(
                            responder.currentLat!!,
                            responder.currentLng!!,
                            null,
                            it,
                        )
                    },
                verifiedAt = responder.verifiedAt,
                createdAt = responder.createdAt,
            )
        }
    }

    /**
     * Verify, reject or disable a responder. Administrator only.
     *
     * This is the trust boundary of the platform. Everything the responder role can do, up to
     * and including reading a stranger's live location during an emergency, follows from an
     * administrator having made this call, so it is audited with the actor recorded.
     */
    @Transactional
    fun setVerification(
        responderId: String,
        status: ResponderVerificationStatus,
        adminId: String,
    ): ResponderAdminResponse {
        val responder = responderRepository.findById(responderId).orElseThrow {
            NotFoundException("No such responder")
        }
        val previous = responder.verificationStatus

        responder.verificationStatus = status
        responder.updatedAt = Instant.now()

        if (status == ResponderVerificationStatus.VERIFIED) {
            responder.verifiedAt = Instant.now()
            responder.verifiedBy = adminId
        } else {
            // Losing verification also takes them off shift, so a rejected or disabled account
            // cannot sit in the available pool.
            responder.availabilityStatus = ResponderAvailability.OFFLINE
            responder.verifiedAt = null
            responder.verifiedBy = null
        }

        responderRepository.save(responder)

        auditService.record(
            actorId = adminId,
            action = AuditService.RESPONDER_VERIFIED,
            targetType = "responder",
            targetId = responderId,
            detail = "$previous -> $status",
        )
        log.info { "Responder $responderId verification $previous -> $status by admin $adminId" }

        val account = userRepository.findById(responder.userId).orElse(null)
        return ResponderAdminResponse(
            id = responder.id,
            userId = responder.userId,
            fullName = account?.fullName ?: "Unknown",
            phone = account?.phone ?: "",
            organization = responder.organization,
            verificationStatus = responder.verificationStatus,
            availabilityStatus = responder.availabilityStatus,
            lastKnownLocation = null,
            verifiedAt = responder.verifiedAt,
            createdAt = responder.createdAt,
        )
    }

    private fun requireResponder(userId: String): Responder =
        responderRepository.findByUserId(userId)
            ?: throw NotFoundException("You are not registered as a responder")
}
