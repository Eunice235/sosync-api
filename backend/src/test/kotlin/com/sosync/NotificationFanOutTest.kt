package com.sosync

import com.sosync.domain.DeliveryStatus
import com.sosync.domain.IncidentStatus
import com.sosync.domain.Language
import com.sosync.domain.NotificationChannel
import com.sosync.domain.NotificationType
import com.sosync.domain.ResponderVerificationStatus
import com.sosync.persistence.NotificationRepository
import com.sosync.service.incident.IncidentService
import com.sosync.support.IntegrationTest
import com.sosync.support.TestFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Alert fan-out: who is reached, and on what channel.
 *
 * The channel rules carry the product in the places it is meant for, so they are the ones worth
 * pinning down. The SMS-only path in particular is easy to break without noticing, because
 * every developer testing this has the app installed.
 */
class NotificationFanOutTest : IntegrationTest() {

    @Autowired
    private lateinit var incidentService: IncidentService

    @Autowired
    private lateinit var notificationRepository: NotificationRepository

    @Test
    @DisplayName("a contact with no account gets SMS only; one with an account gets all three")
    fun channelsDependOnWhetherTheContactHasAnAccount() {
        val reporter = fixtures.user()
        val registered = fixtures.user(name = "Registered")
        fixtures.contact(reporter, name = "Registered", phone = registered.phone)
        fixtures.contact(reporter, name = "Unregistered", phone = "+254712000097")

        val result = incidentService.trigger(
            reporter.id, TestFixtures.NAIROBI_LAT, TestFixtures.NAIROBI_LNG, null, true, null,
        )

        val toRegistered = result.notifications.filter { it.recipientUserId == registered.id }
        assertThat(toRegistered.map { it.channel }).containsExactlyInAnyOrder(
            NotificationChannel.SMS,
            NotificationChannel.IN_APP,
            NotificationChannel.PUSH,
        )

        val toUnregistered = result.notifications.filter {
            it.recipientPhone == "+254712000097"
        }
        // SMS is the only channel that reaches a feature phone with no app and no data.
        assertThat(toUnregistered.map { it.channel }).containsExactly(NotificationChannel.SMS)
    }

    @Test
    @DisplayName("every delivery records the language it went out in")
    fun deliveriesRecordTheirLanguage() {
        val reporter = fixtures.user()
        fixtures.contact(reporter, name = "Someone", phone = "+254712000096")

        val result = incidentService.trigger(reporter.id, null, null, null, true, null)

        // One language today. The field is still populated on every row, so the delivery log
        // stays a truthful record rather than something that has to be inferred later.
        assertThat(result.notifications).isNotEmpty
        assertThat(result.notifications.map { it.language }).containsOnly(Language.EN)
    }

    @Test
    @DisplayName("simulated deliveries say so rather than claiming they were sent")
    fun simulatedDeliveriesAreLabelled() {
        val reporter = fixtures.user()
        fixtures.contact(reporter, name = "Someone", phone = "+254712000096")

        val result = incidentService.trigger(reporter.id, null, null, null, true, null)

        val sms = result.notifications.single { it.channel == NotificationChannel.SMS }
        // A safety tool that overstates delivery is worse than one that admits it.
        assertThat(sms.deliveryStatus).isEqualTo(DeliveryStatus.SIMULATED)

        // The in-app copy genuinely is delivered by being stored, so it is SENT.
        assertThat(
            result.notifications
                .filter { it.channel == NotificationChannel.IN_APP }
                .map { it.deliveryStatus },
        ).allSatisfy { assertThat(it).isEqualTo(DeliveryStatus.SENT) }
    }

    @Test
    @DisplayName("a nearby verified responder is alerted; an unverified one is not")
    fun onlyVerifiedRespondersAreAlerted() {
        val reporter = fixtures.user()
        val (verified, _) = fixtures.responder(organization = "Verified Security")
        val (pending, _) = fixtures.responder(
            organization = "Pending Security",
            verification = ResponderVerificationStatus.PENDING,
        )

        val result = incidentService.trigger(
            reporter.id, TestFixtures.NAIROBI_LAT, TestFixtures.NAIROBI_LNG, null, true, null,
        )

        val recipients = result.notifications.mapNotNull { it.recipientUserId }.toSet()
        assertThat(recipients).contains(verified.id)
        assertThat(recipients).doesNotContain(pending.id)
    }

    @Test
    @DisplayName("a responder whose position is stale is not matched by distance")
    fun staleResponderPositionIsNotUsed() {
        val reporter = fixtures.user()
        val (staleResponder, _) = fixtures.responder(
            organization = "Stale Security",
            locationFresh = false,
        )

        val result = incidentService.trigger(
            reporter.id, TestFixtures.NAIROBI_LAT, TestFixtures.NAIROBI_LNG, null, true, null,
        )

        // A fix from hours ago says nothing about where they are now.
        assertThat(result.notifications.mapNotNull { it.recipientUserId })
            .doesNotContain(staleResponder.id)
    }

    @Test
    @DisplayName("an incident with no location alerts every available responder anyway")
    fun noLocationBroadcastsToAllResponders() {
        val reporter = fixtures.user()
        val (farResponder, _) = fixtures.responder(
            organization = "Far Security",
            lat = -4.0435,
            lng = 39.6682,
        )

        val result = incidentService.trigger(reporter.id, null, null, null, true, null)

        // A report with no location is worse to sit on than to over-broadcast.
        assertThat(result.notifications.mapNotNull { it.recipientUserId })
            .contains(farResponder.id)
    }

    @Test
    @DisplayName("resolution reaches the reporter and the trusted contact")
    fun statusUpdatesReachEveryone() {
        val reporter = fixtures.user(name = "Amina")
        val sister = fixtures.user(name = "Grace")
        fixtures.contact(reporter, name = "Grace", phone = sister.phone)
        val (responderAccount, _) = fixtures.responder()

        val incident = incidentService.trigger(
            reporter.id, TestFixtures.NAIROBI_LAT, TestFixtures.NAIROBI_LNG, null, true, null,
        ).incident
        incidentService.accept(incident.id, responderAccount.id)
        incidentService.advance(
            incident.id, responderAccount.id, IncidentStatus.RESOLVED, null,
        )

        val resolved = notificationRepository
            .findAllByIncidentIdOrderByCreatedAtAsc(incident.id)
            .filter { it.type == NotificationType.INCIDENT_RESOLVED }

        val toReporter = resolved.single { it.recipientUserId == reporter.id }
        assertThat(toReporter.body).contains("resolved")

        // Nobody who was told about the emergency is left watching a stale alert.
        assertThat(resolved.filter { it.recipientUserId == sister.id }).isNotEmpty
    }

    @Test
    @DisplayName("the reporter is never sent an SMS or push about their own silent emergency")
    fun reporterIsNotGivenAwayBySms() {
        val reporter = fixtures.user()
        fixtures.contact(reporter)
        val (responderAccount, _) = fixtures.responder()

        val incident = incidentService.trigger(
            reporter.id, TestFixtures.NAIROBI_LAT, TestFixtures.NAIROBI_LNG, null, true, null,
        ).incident
        incidentService.accept(incident.id, responderAccount.id)

        // An SMS or a push arriving mid-emergency is exactly the visible, audible thing the
        // whole design exists to avoid.
        val toReporter = notificationRepository
            .findAllByIncidentIdOrderByCreatedAtAsc(incident.id)
            .filter { it.recipientUserId == reporter.id }

        assertThat(toReporter).isNotEmpty
        assertThat(toReporter.map { it.channel }).containsOnly(NotificationChannel.IN_APP)
    }

    @Test
    @DisplayName("an incident raised with no contacts still succeeds, and reaches nobody")
    fun noContactsIsNotAFailure() {
        val reporter = fixtures.user()

        val result = incidentService.trigger(reporter.id, null, null, null, true, null)

        assertThat(result.incident.id).isNotBlank()
        assertThat(result.notifications).isEmpty()
    }
}
