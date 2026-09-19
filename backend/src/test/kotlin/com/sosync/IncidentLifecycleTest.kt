package com.sosync

import com.sosync.common.BadRequestException
import com.sosync.common.ConflictException
import com.sosync.common.ForbiddenException
import com.sosync.domain.IncidentEventType
import com.sosync.domain.IncidentStatus
import com.sosync.domain.ResponderAvailability
import com.sosync.domain.ResponderVerificationStatus
import com.sosync.persistence.IncidentEventRepository
import com.sosync.persistence.IncidentRepository
import com.sosync.persistence.LocationUpdateRepository
import com.sosync.service.incident.IncidentService
import com.sosync.support.IntegrationTest
import com.sosync.support.TestFixtures
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The emergency lifecycle: raising an incident, moving it along, and the rules that stop it
 * going wrong.
 */
class IncidentLifecycleTest : IntegrationTest() {

    @Autowired
    private lateinit var incidentService: IncidentService

    @Autowired
    private lateinit var incidentRepository: IncidentRepository

    @Autowired
    private lateinit var eventRepository: IncidentEventRepository

    @Autowired
    private lateinit var locationRepository: LocationUpdateRepository

    @Test
    @DisplayName("raising an SOS creates an incident, captures the fix, and starts a timeline")
    fun triggerCreatesIncident() {
        val reporter = fixtures.user()
        fixtures.contact(reporter, name = "Sister")

        val result = incidentService.trigger(
            userId = reporter.id,
            latitude = TestFixtures.NAIROBI_LAT,
            longitude = TestFixtures.NAIROBI_LNG,
            accuracy = 15.0,
            silent = true,
            note = "Taxi changed route",
        )

        assertThat(result.alreadyActive).isFalse()
        assertThat(result.incident.status).isEqualTo(IncidentStatus.TRIGGERED)
        assertThat(result.incident.silent).isTrue()
        assertThat(result.incident.triggerLat).isEqualTo(TestFixtures.NAIROBI_LAT)
        assertThat(result.incident.lastLat).isEqualTo(TestFixtures.NAIROBI_LAT)
        assertThat(result.notifications).isNotEmpty()

        assertThat(locationRepository.countByIncidentId(result.incident.id)).isEqualTo(1)
        assertThat(
            eventRepository.findAllByIncidentIdOrderByOccurredAtAsc(result.incident.id)
                .map { it.eventType },
        ).contains(IncidentEventType.TRIGGERED)
    }

    @Test
    @DisplayName("an SOS with no GPS fix still goes out")
    fun triggerWithoutLocation() {
        val reporter = fixtures.user()
        fixtures.contact(reporter)

        val result = incidentService.trigger(reporter.id, null, null, null, true, null)

        // Reaching people late with a location beats reaching them never because the phone was
        // indoors, so the absence of a fix must not block the alert.
        assertThat(result.incident.status).isEqualTo(IncidentStatus.TRIGGERED)
        assertThat(result.incident.triggerLat).isNull()
        assertThat(result.notifications).isNotEmpty()
        assertThat(locationRepository.countByIncidentId(result.incident.id)).isZero()
    }

    @Test
    @DisplayName("coordinates of exactly (0, 0) are treated as no fix, not as the Gulf of Guinea")
    fun triggerWithZeroZeroIsTreatedAsNoFix() {
        val reporter = fixtures.user()
        fixtures.contact(reporter)

        val result = incidentService.trigger(reporter.id, 0.0, 0.0, null, true, null)

        assertThat(result.incident.triggerLat).isNull()
        assertThat(result.incident.lastLat).isNull()
    }

    @Test
    @DisplayName("pressing the button twice joins the running emergency instead of starting a second")
    fun repeatedPressIsIdempotent() {
        val reporter = fixtures.user()
        fixtures.contact(reporter)

        val first = incidentService.trigger(
            reporter.id, TestFixtures.NAIROBI_LAT, TestFixtures.NAIROBI_LNG, null, true, null,
        )
        val second = incidentService.trigger(
            reporter.id, -1.2930, 36.8228, null, true, null,
        )

        assertThat(second.alreadyActive).isTrue()
        assertThat(second.incident.id).isEqualTo(first.incident.id)
        assertThat(incidentRepository.findAll()).hasSize(1)

        // The second press is still a newer, often better, fix and is recorded as one.
        assertThat(locationRepository.countByIncidentId(first.incident.id)).isEqualTo(2)
        assertThat(second.incident.lastLat).isEqualTo(-1.2930)

        // No second fan-out: the contacts were already told.
        assertThat(second.notifications).isEmpty()
    }

    @Test
    @DisplayName("a new emergency can be raised once the previous one is closed")
    fun newIncidentAfterClose() {
        val reporter = fixtures.user()
        fixtures.contact(reporter)

        val first = incidentService.trigger(reporter.id, null, null, null, true, null)
        incidentService.cancel(first.incident.id, reporter.id, TestFixtures.PIN, "false alarm")

        val second = incidentService.trigger(reporter.id, null, null, null, true, null)

        assertThat(second.alreadyActive).isFalse()
        assertThat(second.incident.id).isNotEqualTo(first.incident.id)
    }

    @Test
    @DisplayName("first responder to accept wins; the second is told, not silently overwritten")
    fun acceptIsFirstComeFirstServed() {
        val reporter = fixtures.user()
        fixtures.contact(reporter)
        val (firstAccount, firstResponder) = fixtures.responder(organization = "First Security")
        val (secondAccount, _) = fixtures.responder(organization = "Second Security")

        val incident = incidentService.trigger(
            reporter.id, TestFixtures.NAIROBI_LAT, TestFixtures.NAIROBI_LNG, null, true, null,
        ).incident

        val accepted = incidentService.accept(incident.id, firstAccount.id)
        assertThat(accepted.status).isEqualTo(IncidentStatus.ACCEPTED)
        assertThat(accepted.assignedResponderId).isEqualTo(firstResponder.id)
        assertThat(accepted.acceptedAt).isNotNull()

        assertThatThrownBy { incidentService.accept(incident.id, secondAccount.id) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("already taken")

        assertThat(incidentRepository.findById(incident.id).get().assignedResponderId)
            .isEqualTo(firstResponder.id)
    }

    @Test
    @DisplayName("an unverified responder cannot accept, however available they mark themselves")
    fun unverifiedResponderCannotAccept() {
        val reporter = fixtures.user()
        fixtures.contact(reporter)
        val (pendingAccount, _) = fixtures.responder(
            verification = ResponderVerificationStatus.PENDING,
            availability = ResponderAvailability.AVAILABLE,
        )

        val incident = incidentService.trigger(
            reporter.id, TestFixtures.NAIROBI_LAT, TestFixtures.NAIROBI_LNG, null, true, null,
        ).incident

        assertThatThrownBy { incidentService.accept(incident.id, pendingAccount.id) }
            .isInstanceOf(ForbiddenException::class.java)
            .hasMessageContaining("PENDING")
    }

    @Test
    @DisplayName("a responder disabled after the alert went out can no longer claim it")
    fun disabledResponderCannotAccept() {
        val reporter = fixtures.user()
        fixtures.contact(reporter)
        val (account, _) = fixtures.responder(
            verification = ResponderVerificationStatus.DISABLED,
        )

        val incident = incidentService.trigger(reporter.id, null, null, null, true, null).incident

        assertThatThrownBy { incidentService.accept(incident.id, account.id) }
            .isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    @DisplayName("status moves forward only")
    fun transitionsAreForwardOnly() {
        val reporter = fixtures.user()
        fixtures.contact(reporter)
        val (responderAccount, _) = fixtures.responder()

        val incident = incidentService.trigger(
            reporter.id, TestFixtures.NAIROBI_LAT, TestFixtures.NAIROBI_LNG, null, true, null,
        ).incident

        incidentService.accept(incident.id, responderAccount.id)
        incidentService.advance(
            incident.id, responderAccount.id, IncidentStatus.RESPONDING, null,
        )
        val arrived = incidentService.advance(
            incident.id, responderAccount.id, IncidentStatus.ARRIVED, null,
        )
        assertThat(arrived.arrivedAt).isNotNull()

        // Going back to RESPONDING would make the timeline unreadable, which defeats the point
        // of keeping one.
        assertThatThrownBy {
            incidentService.advance(
                incident.id, responderAccount.id, IncidentStatus.RESPONDING, null,
            )
        }.isInstanceOf(BadRequestException::class.java)

        val resolved = incidentService.advance(
            incident.id, responderAccount.id, IncidentStatus.RESOLVED, "Walked her home",
        )
        assertThat(resolved.status).isEqualTo(IncidentStatus.RESOLVED)
        assertThat(resolved.closedAt).isNotNull()
        assertThat(resolved.resolutionNote).isEqualTo("Walked her home")

        // Terminal means terminal.
        assertThatThrownBy {
            incidentService.advance(
                incident.id, responderAccount.id, IncidentStatus.ARRIVED, null,
            )
        }.isInstanceOf(ConflictException::class.java)
    }

    @Test
    @DisplayName("a responder cannot move an incident assigned to somebody else")
    fun onlyAssignedResponderCanAdvance() {
        val reporter = fixtures.user()
        fixtures.contact(reporter)
        val (owner, _) = fixtures.responder(organization = "Owner Security")
        val (other, _) = fixtures.responder(organization = "Other Security")

        val incident = incidentService.trigger(reporter.id, null, null, null, true, null).incident
        incidentService.accept(incident.id, owner.id)

        assertThatThrownBy {
            incidentService.advance(incident.id, other.id, IncidentStatus.RESPONDING, null)
        }.isInstanceOf(ForbiddenException::class.java)
            .hasMessageContaining("another responder")
    }

    @Test
    @DisplayName("location is accepted while the incident is open and refused once it closes")
    fun locationStopsAtClose() {
        val reporter = fixtures.user()
        fixtures.contact(reporter)

        val incident = incidentService.trigger(
            reporter.id, TestFixtures.NAIROBI_LAT, TestFixtures.NAIROBI_LNG, null, true, null,
        ).incident

        val updated = incidentService.recordLocation(
            incident.id, reporter.id, -1.2930, 36.8228, 12.0, null,
        )
        assertThat(updated.lastLat).isEqualTo(-1.2930)
        assertThat(locationRepository.countByIncidentId(incident.id)).isEqualTo(2)

        incidentService.cancel(incident.id, reporter.id, TestFixtures.PIN, "safe now")

        // This is the mechanism behind the promise that sharing stops: there is no code path
        // that stores a position outside an open incident.
        assertThatThrownBy {
            incidentService.recordLocation(
                incident.id, reporter.id, -1.2940, 36.8240, null, null,
            )
        }.isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("closed")

        assertThat(locationRepository.countByIncidentId(incident.id)).isEqualTo(2)
    }

    @Test
    @DisplayName("an out-of-order fix from a phone that was offline never overwrites a newer one")
    fun staleFixDoesNotOverwriteNewerPosition() {
        val reporter = fixtures.user()
        fixtures.contact(reporter)

        val incident = incidentService.trigger(
            reporter.id, TestFixtures.NAIROBI_LAT, TestFixtures.NAIROBI_LNG, null, true, null,
        ).incident

        incidentService.recordLocation(incident.id, reporter.id, -1.2930, 36.8228, null, null)

        // A fix captured ten minutes ago, uploaded now because coverage just came back.
        val result = incidentService.recordLocation(
            incident.id,
            reporter.id,
            -1.2800,
            36.8100,
            null,
            Instant.now().minus(10, ChronoUnit.MINUTES),
        )

        // Stored in the trail, but the denormalised "where are they now" stays on the newer fix.
        assertThat(result.lastLat).isEqualTo(-1.2930)
        assertThat(locationRepository.countByIncidentId(incident.id)).isEqualTo(3)
    }

    @Test
    @DisplayName("implausible coordinates are refused outright")
    fun implausibleLocationRejected() {
        val reporter = fixtures.user()
        val incident = incidentService.trigger(reporter.id, null, null, null, true, null).incident

        assertThatThrownBy {
            incidentService.recordLocation(incident.id, reporter.id, 0.0, 0.0, null, null)
        }.isInstanceOf(BadRequestException::class.java)
    }

    @Test
    @DisplayName("only the reporter may report a position on their own incident")
    fun outsiderCannotReportLocation() {
        val reporter = fixtures.user()
        val stranger = fixtures.user(name = "Stranger")

        val incident = incidentService.trigger(reporter.id, null, null, null, true, null).incident

        assertThatThrownBy {
            incidentService.recordLocation(
                incident.id, stranger.id, TestFixtures.NAIROBI_LAT, TestFixtures.NAIROBI_LNG,
                null, null,
            )
        }.isInstanceOf(ForbiddenException::class.java)
    }
}
