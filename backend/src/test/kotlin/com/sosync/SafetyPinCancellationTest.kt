package com.sosync

import com.sosync.common.ConflictException
import com.sosync.common.ForbiddenException
import com.sosync.common.InvalidSafetyPinException
import com.sosync.domain.IncidentStatus
import com.sosync.persistence.AuditRepository
import com.sosync.persistence.IncidentRepository
import com.sosync.service.incident.IncidentService
import com.sosync.support.IntegrationTest
import com.sosync.support.TestFixtures
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Cancellation.
 *
 * The most security-sensitive operation in the system, because whoever can cancel an alert can
 * call off the response. Everything here is checking that the only person who can do it is the
 * one who raised it, with something only they know, and that failing leaves help on its way.
 */
class SafetyPinCancellationTest : IntegrationTest() {

    @Autowired
    private lateinit var incidentService: IncidentService

    @Autowired
    private lateinit var incidentRepository: IncidentRepository

    @Autowired
    private lateinit var auditRepository: AuditRepository

    @Test
    @DisplayName("the correct PIN stands the emergency down and stops location sharing")
    fun correctPinCancels() {
        val reporter = fixtures.user()
        fixtures.contact(reporter)
        val incident = incidentService.trigger(reporter.id, null, null, null, true, null).incident

        val cancelled = incidentService.cancel(
            incident.id, reporter.id, TestFixtures.PIN, "False alarm, I am safe",
        )

        assertThat(cancelled.status).isEqualTo(IncidentStatus.CANCELLED)
        assertThat(cancelled.cancelReason).isEqualTo("False alarm, I am safe")
        assertThat(cancelled.closedAt).isNotNull()
        assertThat(cancelled.isOpen).isFalse()
    }

    @Test
    @DisplayName("a wrong PIN leaves the emergency running")
    fun wrongPinLeavesIncidentOpen() {
        val reporter = fixtures.user()
        fixtures.contact(reporter)
        val incident = incidentService.trigger(reporter.id, null, null, null, true, null).incident

        assertThatThrownBy {
            incidentService.cancel(incident.id, reporter.id, "0000", "nope")
        }.isInstanceOf(InvalidSafetyPinException::class.java)

        // The whole point: somebody who grabs the phone has the app but not the PIN, and help
        // keeps coming.
        val reloaded = incidentRepository.findById(incident.id).get()
        assertThat(reloaded.status).isEqualTo(IncidentStatus.TRIGGERED)
        assertThat(reloaded.isOpen).isTrue()
        assertThat(reloaded.closedAt).isNull()
        assertThat(reloaded.cancelReason).isNull()
    }

    @Test
    @DisplayName("a failed cancellation is recorded, so a stolen phone leaves a trace")
    fun failedAttemptIsAudited() {
        val reporter = fixtures.user()
        val incident = incidentService.trigger(reporter.id, null, null, null, true, null).incident

        runCatching { incidentService.cancel(incident.id, reporter.id, "9999", null) }

        val entries = auditRepository
            .findAllByTargetTypeAndTargetIdOrderByOccurredAtDesc("incident", incident.id)
        assertThat(entries.map { it.detail })
            .anySatisfy { assertThat(it).contains("rejected") }
    }

    @Test
    @DisplayName("a trusted contact cannot cancel somebody else's emergency")
    fun contactCannotCancel() {
        val reporter = fixtures.user()
        val sister = fixtures.user(name = "Sister")
        fixtures.contact(reporter, name = "Sister", phone = sister.phone)

        val incident = incidentService.trigger(reporter.id, null, null, null, true, null).incident

        // She can watch the incident, and she still cannot call off the response.
        assertThatThrownBy {
            incidentService.cancel(incident.id, sister.id, TestFixtures.PIN, null)
        }.isInstanceOf(ForbiddenException::class.java)

        assertThat(incidentRepository.findById(incident.id).get().isOpen).isTrue()
    }

    @Test
    @DisplayName("the assigned responder cannot cancel either; they resolve instead")
    fun responderCannotCancel() {
        val reporter = fixtures.user()
        fixtures.contact(reporter)
        val (responderAccount, _) = fixtures.responder()

        val incident = incidentService.trigger(
            reporter.id, TestFixtures.NAIROBI_LAT, TestFixtures.NAIROBI_LNG, null, true, null,
        ).incident
        incidentService.accept(incident.id, responderAccount.id)

        assertThatThrownBy {
            incidentService.cancel(incident.id, responderAccount.id, TestFixtures.PIN, null)
        }.isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    @DisplayName("with no PIN set, the account password is accepted instead")
    fun passwordFallbackWhenNoPinSet() {
        // An account that has not finished setup must not be left unable to cancel at all.
        val reporter = fixtures.user(safetyPin = null)
        val incident = incidentService.trigger(reporter.id, null, null, null, true, null).incident

        val cancelled = incidentService.cancel(
            incident.id, reporter.id, TestFixtures.PASSWORD, null,
        )

        assertThat(cancelled.status).isEqualTo(IncidentStatus.CANCELLED)
    }

    @Test
    @DisplayName("once a PIN is set, the password no longer cancels")
    fun passwordRejectedWhenPinIsSet() {
        val reporter = fixtures.user(safetyPin = TestFixtures.PIN)
        val incident = incidentService.trigger(reporter.id, null, null, null, true, null).incident

        assertThatThrownBy {
            incidentService.cancel(incident.id, reporter.id, TestFixtures.PASSWORD, null)
        }.isInstanceOf(InvalidSafetyPinException::class.java)
    }

    @Test
    @DisplayName("an already-closed incident cannot be cancelled again")
    fun cannotCancelTwice() {
        val reporter = fixtures.user()
        val incident = incidentService.trigger(reporter.id, null, null, null, true, null).incident
        incidentService.cancel(incident.id, reporter.id, TestFixtures.PIN, null)

        assertThatThrownBy {
            incidentService.cancel(incident.id, reporter.id, TestFixtures.PIN, null)
        }.isInstanceOf(ConflictException::class.java)
    }
}
