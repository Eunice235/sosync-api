package com.sosync

import com.sosync.common.NotFoundException
import com.sosync.domain.IncidentStatus
import com.sosync.domain.ResponderAvailability
import com.sosync.domain.ResponderVerificationStatus
import com.sosync.service.incident.IncidentAccessPolicy
import com.sosync.service.incident.IncidentQueryService
import com.sosync.service.incident.IncidentService
import com.sosync.service.incident.ViewerRelationship
import com.sosync.support.IntegrationTest
import com.sosync.support.TestFixtures
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Who may look at an emergency, and for how long.
 *
 * Location is the sensitive capability in SOSync, so these are the tests most worth having.
 * They all go through [IncidentAccessPolicy], which is deliberately the only authority on the
 * question: scattering these checks across controllers fails by one endpoint quietly missing
 * one, and nobody notices until it matters.
 */
class IncidentAccessPolicyTest : IntegrationTest() {

    @Autowired
    private lateinit var incidentService: IncidentService

    @Autowired
    private lateinit var queryService: IncidentQueryService

    @Autowired
    private lateinit var accessPolicy: IncidentAccessPolicy

    @Test
    @DisplayName("the reporter sees their own incident")
    fun reporterCanView() {
        val reporter = fixtures.user()
        val incident = incidentService.trigger(
            reporter.id, TestFixtures.NAIROBI_LAT, TestFixtures.NAIROBI_LNG, null, true, null,
        ).incident

        val (_, relationship) = accessPolicy.requireViewable(incident.id, reporter.id)
        assertThat(relationship).isEqualTo(ViewerRelationship.REPORTER)
    }

    @Test
    @DisplayName("a nominated contact is recognised by phone number, with no account linking")
    fun trustedContactCanView() {
        val reporter = fixtures.user()
        val sister = fixtures.user(name = "Sister")
        fixtures.contact(reporter, name = "Sister", phone = sister.phone)

        val incident = incidentService.trigger(
            reporter.id, TestFixtures.NAIROBI_LAT, TestFixtures.NAIROBI_LNG, null, true, null,
        ).incident

        val (_, relationship) = accessPolicy.requireViewable(incident.id, sister.id)
        assertThat(relationship).isEqualTo(ViewerRelationship.TRUSTED_CONTACT)
    }

    @Test
    @DisplayName("a household member sees it without being on the contact list")
    fun familyMemberCanView() {
        val reporter = fixtures.user()
        val brother = fixtures.user(name = "Brother")
        fixtures.family(reporter, brother)

        val incident = incidentService.trigger(reporter.id, null, null, null, true, null).incident

        val (_, relationship) = accessPolicy.requireViewable(incident.id, brother.id)
        assertThat(relationship).isEqualTo(ViewerRelationship.FAMILY_MEMBER)
    }

    @Test
    @DisplayName("an unrelated caller gets 'not found', not 'forbidden'")
    fun strangerGetsNotFound() {
        val reporter = fixtures.user()
        val stranger = fixtures.user(name = "Stranger")

        val incident = incidentService.trigger(reporter.id, null, null, null, true, null).incident

        // A 403 would confirm the id exists, which tells a stranger somebody raised an
        // emergency. That is itself information they are not entitled to.
        assertThatThrownBy { accessPolicy.requireViewable(incident.id, stranger.id) }
            .isInstanceOf(NotFoundException::class.java)
            .hasMessageContaining("No such incident")
    }

    @Test
    @DisplayName("an available responder may read an unclaimed alert, and loses it once taken")
    fun responderVisibilityFollowsAssignment() {
        val reporter = fixtures.user()
        fixtures.contact(reporter)
        val (takerAccount, _) = fixtures.responder(organization = "Taker Security")
        val (otherAccount, _) = fixtures.responder(organization = "Other Security")

        val incident = incidentService.trigger(
            reporter.id, TestFixtures.NAIROBI_LAT, TestFixtures.NAIROBI_LNG, null, true, null,
        ).incident

        // While unclaimed, anyone who could take it can read it.
        assertThat(accessPolicy.requireViewable(incident.id, otherAccount.id).second)
            .isEqualTo(ViewerRelationship.AVAILABLE_RESPONDER)

        incidentService.accept(incident.id, takerAccount.id)

        assertThat(accessPolicy.requireViewable(incident.id, takerAccount.id).second)
            .isEqualTo(ViewerRelationship.ASSIGNED_RESPONDER)

        // The responder who did not take it loses sight of it entirely.
        assertThatThrownBy { accessPolicy.requireViewable(incident.id, otherAccount.id) }
            .isInstanceOf(NotFoundException::class.java)
    }

    @Test
    @DisplayName("an unverified responder cannot read even an unclaimed alert")
    fun unverifiedResponderCannotView() {
        val reporter = fixtures.user()
        val (pendingAccount, _) = fixtures.responder(
            verification = ResponderVerificationStatus.PENDING,
            availability = ResponderAvailability.AVAILABLE,
        )

        val incident = incidentService.trigger(reporter.id, null, null, null, true, null).incident

        assertThatThrownBy { accessPolicy.requireViewable(incident.id, pendingAccount.id) }
            .isInstanceOf(NotFoundException::class.java)
    }

    @Test
    @DisplayName("location sharing with a contact ends when the incident closes")
    fun locationSharingEndsAtClose() {
        val reporter = fixtures.user()
        val sister = fixtures.user(name = "Sister")
        fixtures.contact(reporter, name = "Sister", phone = sister.phone)

        val incident = incidentService.trigger(
            reporter.id, TestFixtures.NAIROBI_LAT, TestFixtures.NAIROBI_LNG, null, true, null,
        ).incident

        // Open: she can follow the position, and the payload carries it.
        val whileOpen = queryService.detail(incident.id, sister.id, includeTrail = true)
        assertThat(whileOpen.canViewLocation).isTrue()
        assertThat(whileOpen.location).isNotNull()
        assertThat(queryService.locationTrail(incident.id, sister.id)).isNotEmpty()

        incidentService.cancel(incident.id, reporter.id, TestFixtures.PIN, "safe")

        // Closed: consent to being followed was consent for the duration of an emergency.
        val afterClose = queryService.detail(incident.id, sister.id, includeTrail = true)
        assertThat(afterClose.status).isEqualTo(IncidentStatus.CANCELLED)
        assertThat(afterClose.canViewLocation).isFalse()
        assertThat(afterClose.location).isNull()
        assertThat(afterClose.locationTrail).isNull()
        assertThat(queryService.locationTrail(incident.id, sister.id)).isEmpty()

        // She can still see that it happened and how it ended. Only the position is withheld.
        assertThat(afterClose.cancelReason).isEqualTo("safe")
    }

    @Test
    @DisplayName("the reporter keeps their own location history after the incident closes")
    fun reporterKeepsOwnHistory() {
        val reporter = fixtures.user()
        val incident = incidentService.trigger(
            reporter.id, TestFixtures.NAIROBI_LAT, TestFixtures.NAIROBI_LNG, null, true, null,
        ).incident

        incidentService.cancel(incident.id, reporter.id, TestFixtures.PIN, null)

        val detail = queryService.detail(incident.id, reporter.id, includeTrail = true)
        assertThat(detail.canViewLocation).isTrue()
        assertThat(queryService.locationTrail(incident.id, reporter.id)).isNotEmpty()
    }

    @Test
    @DisplayName("the contact inbox shows emergencies raised by people who nominated you")
    fun contactInboxListsWatchedIncidents() {
        val reporter = fixtures.user(name = "Amina")
        val sister = fixtures.user(name = "Grace")
        fixtures.contact(reporter, name = "Grace", phone = sister.phone)

        incidentService.trigger(
            reporter.id, TestFixtures.NAIROBI_LAT, TestFixtures.NAIROBI_LNG, null, true, null,
        )

        val inbox = queryService.alertsForContact(sister.id, includeClosed = false)
        assertThat(inbox).hasSize(1)
        assertThat(inbox.first().reporter.fullName).isEqualTo("Amina")
        assertThat(inbox.first().viewerRelationship)
            .isEqualTo(ViewerRelationship.TRUSTED_CONTACT)
    }

    @Test
    @DisplayName("somebody who nominates nobody has an empty inbox")
    fun emptyInboxForUnrelatedUser() {
        val reporter = fixtures.user()
        val stranger = fixtures.user(name = "Stranger")
        incidentService.trigger(reporter.id, null, null, null, true, null)

        assertThat(queryService.alertsForContact(stranger.id, includeClosed = true)).isEmpty()
    }
}
