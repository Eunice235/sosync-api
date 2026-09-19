package com.sosync.service.responder

import com.sosync.common.Geo
import com.sosync.config.SosyncProperties
import com.sosync.domain.Incident
import com.sosync.domain.Responder
import com.sosync.domain.ResponderAvailability
import com.sosync.domain.ResponderVerificationStatus
import com.sosync.persistence.ResponderRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit

private val log = KotlinLogging.logger {}

/** A responder paired with how far away they were when the incident was raised. */
data class MatchedResponder(
    val responder: Responder,
    val distanceKm: Double?,
)

/**
 * Decides which responders hear about an incident.
 *
 * Three filters, in order of how much they matter:
 *
 * 1. **Verified.** An unverified responder has not been vetted by an administrator and is sent
 *    nothing at all. This is the trust boundary of the whole system.
 * 2. **Available.** Someone marked BUSY or OFFLINE will not turn up, and filling their list
 *    with alerts they cannot take makes the list useless.
 * 3. **Nearby, with a fresh position.** Distance is only meaningful if the responder position
 *    it is measured from is recent. A fix from this morning says nothing about where they are
 *    now, so a stale responder is excluded rather than assumed to be still there.
 *
 * If the incident itself has no usable coordinates, distance cannot be computed. In that case
 * every verified and available responder is alerted: a report with no location is worse to sit
 * on than to over-broadcast.
 */
@Service
class ResponderMatcher(
    private val responderRepository: ResponderRepository,
    private val properties: SosyncProperties,
) {

    fun matchFor(incident: Incident): List<MatchedResponder> {
        val candidates = responderRepository.findAllByVerificationStatusAndAvailabilityStatus(
            ResponderVerificationStatus.VERIFIED,
            ResponderAvailability.AVAILABLE,
        )

        val lat = incident.lastLat ?: incident.triggerLat
        val lng = incident.lastLng ?: incident.triggerLng

        if (!Geo.isPlausible(lat, lng)) {
            log.warn {
                "Incident ${incident.id} has no usable location; alerting all " +
                    "${candidates.size} available responders"
            }
            return candidates.map { MatchedResponder(it, null) }
        }

        val staleBefore = Instant.now()
            .minus(properties.location.responderFreshnessMinutes, ChronoUnit.MINUTES)

        return candidates
            .mapNotNull { responder ->
                val fresh = responder.locationUpdatedAt?.isAfter(staleBefore) == true
                val hasPosition = Geo.isPlausible(responder.currentLat, responder.currentLng)

                if (!fresh || !hasPosition) return@mapNotNull null

                val distance = Geo.distanceKmRounded(
                    lat!!,
                    lng!!,
                    responder.currentLat!!,
                    responder.currentLng!!,
                )

                if (distance > properties.location.nearbyRadiusKm) null
                else MatchedResponder(responder, distance)
            }
            .sortedBy { it.distanceKm }
    }

    /** Distance from a responder to an incident, for display on the alert list. */
    fun distanceTo(responder: Responder, incident: Incident): Double? {
        val lat = incident.lastLat ?: incident.triggerLat
        val lng = incident.lastLng ?: incident.triggerLng
        if (!Geo.isPlausible(lat, lng)) return null
        if (!Geo.isPlausible(responder.currentLat, responder.currentLng)) return null
        return Geo.distanceKmRounded(lat!!, lng!!, responder.currentLat!!, responder.currentLng!!)
    }
}
