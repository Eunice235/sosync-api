package com.sosync.service.notification

import com.sosync.domain.Incident
import com.sosync.domain.IncidentStatus
import com.sosync.domain.Language
import com.sosync.domain.NotificationType
import com.sosync.domain.Role
import com.sosync.domain.User
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Instant

/**
 * Message wording and assembly.
 *
 * Pure unit tests: no database, no Spring. They run in milliseconds, which matters because
 * these are the assertions worth running on every save.
 */
class AlertStringsTest {

    private fun reporter(
        name: String = "Amina Wanjiru",
        note: String? = null,
    ) = User(
        fullName = name,
        phone = "+254712000001",
        passwordHash = "x",
        role = Role.USER,
        emergencyNote = note,
    )

    private fun incident(
        lat: Double? = -1.2921,
        lng: Double? = 36.8219,
        note: String? = null,
    ) = Incident(
        userId = "user1",
        status = IncidentStatus.TRIGGERED,
        triggerLat = lat,
        triggerLng = lng,
        lastLat = lat,
        lastLng = lng,
        lastLocationAt = if (lat != null) Instant.now() else null,
        note = note,
    )

    @ParameterizedTest
    @EnumSource(Language::class)
    @DisplayName("every language has a complete catalogue with no blank or leftover strings")
    fun everyCatalogueIsComplete(language: Language) {
        val s = AlertStrings.of(language)
        val produced = listOf(
            s.sosTitle("Amina"),
            s.sosBody("Amina"),
            s.smsNeedsHelp("Amina"),
            s.responderAlertTitle(" 2km"),
            s.responderAlertBody("Amina", " 2km"),
            s.openAppToAccept(),
            s.smsNewEmergency(" 2km"),
            s.acceptedTitle(),
            s.acceptedBody("Watch", "Amina"),
            s.respondingTitle(),
            s.respondingBody("Watch", "Amina"),
            s.arrivedTitle(),
            s.arrivedBody("Watch", "Amina"),
            s.resolvedTitle(),
            s.resolvedBody("Amina", "Watch"),
            s.cancelledTitle(),
            s.cancelledBody("Amina"),
            s.noLocationYet(),
            s.locationUnavailableSms(),
            s.locationPrefix("https://maps.example"),
            s.notePrefix("asthmatic"),
            s.reasonPrefix("false alarm"),
            s.sharingStopped(),
            s.doNotReply(),
            s.aResponder(),
            s.sosSummaryAlreadyActive(),
            s.sosSummaryNobodyReached(),
            s.sosSummarySent(3),
        )

        assertThat(produced).allSatisfy {
            assertThat(it.isNotBlank()).isTrue()
            // A catalogue copied and half-translated is the failure mode worth catching.
            assertThat(it).doesNotContain("TODO")
            assertThat(it).doesNotContain("{")
        }
        assertThat(s.language).isEqualTo(language)
    }

    @ParameterizedTest
    @EnumSource(Language::class)
    @DisplayName("every alert names the person and carries the location link")
    fun everyAlertIsActionable(language: Language) {
        val message = AlertMessages.sosTriggered(incident(), reporter(), language)

        // A notification that says only "emergency" leaves the recipient with nothing to do.
        assertThat(message.title).contains("Amina Wanjiru")
        assertThat(message.body).contains("Amina Wanjiru")
        assertThat(message.body).contains("https://www.google.com/maps")
        assertThat(message.smsBody).contains("Amina Wanjiru")
        assertThat(message.smsBody).contains("https://www.google.com/maps")
        assertThat(message.language).isEqualTo(language)
    }

    @ParameterizedTest
    @EnumSource(Language::class)
    @DisplayName("a missing GPS fix is stated, not silently omitted")
    fun noLocationIsStatedExplicitly(language: Language) {
        val message = AlertMessages.sosTriggered(
            incident(lat = null, lng = null), reporter(), language,
        )

        // The recipient must be able to tell "we do not know where they are" from
        // "we forgot to tell you where they are".
        assertThat(message.body).contains(AlertStrings.of(language).noLocationYet().trim())
        assertThat(message.smsBody)
            .contains(AlertStrings.of(language).locationUnavailableSms())
        assertThat(message.body).doesNotContain("https://")
    }

    @ParameterizedTest
    @EnumSource(Language::class)
    @DisplayName("SMS bodies stay inside one segment for a typical alert")
    fun smsBodiesFitOneSegment(language: Language) {
        val message = AlertMessages.sosTriggered(incident(), reporter(), language)

        // Each segment is paid for separately, and this is the channel that reaches people with
        // no data. A little headroom is left for longer names.
        assertThat(message.smsBody.length).isLessThanOrEqualTo(160)
    }

    @Test
    @DisplayName("medical notes and what the reporter typed both reach the recipient")
    fun contextIsCarriedThrough() {
        val message = AlertMessages.sosTriggered(
            incident(note = "Driver will not stop"),
            reporter(note = "Asthmatic, carries an inhaler"),
            Language.EN,
        )

        assertThat(message.body).contains("Asthmatic, carries an inhaler")
        assertThat(message.body).contains("Driver will not stop")
    }

    @Test
    @DisplayName("a responder alert leads with distance and tells them what to do")
    fun responderAlertIsUseful() {
        val message = AlertMessages.sosForResponder(
            incident(), reporter(), distanceKm = 1.2, language = Language.EN,
        )

        assertThat(message.title).contains("1.2km")
        assertThat(message.body).contains("Open SOSync to accept")
    }

    @Test
    @DisplayName("a responder alert with no distance reads cleanly rather than saying 'nullkm'")
    fun responderAlertWithoutDistance() {
        val message = AlertMessages.sosForResponder(
            incident(lat = null, lng = null), reporter(), distanceKm = null,
            language = Language.EN,
        )

        assertThat(message.title).doesNotContain("null")
        assertThat(message.body).doesNotContain("null")
    }

    @ParameterizedTest
    @EnumSource(Language::class)
    @DisplayName("closing messages say that location sharing has stopped")
    fun closingMessagesMentionSharingStopped(language: Language) {
        val resolved = AlertMessages.statusChange(
            incident(), reporter(), NotificationType.INCIDENT_RESOLVED, "Watch", language,
        )
        val cancelled = AlertMessages.statusChange(
            incident(), reporter(), NotificationType.INCIDENT_CANCELLED, null, language,
        )

        val stopped = AlertStrings.of(language).sharingStopped().trim()
        assertThat(resolved.body).contains(stopped)
        assertThat(cancelled.body).contains(stopped)
    }

    @Test
    @DisplayName("an update with no named responder falls back to a generic label")
    fun genericResponderLabel() {
        val message = AlertMessages.statusChange(
            incident(), reporter(), NotificationType.RESPONDER_ACCEPTED, null, Language.EN,
        )

        assertThat(message.body).startsWith("A responder")
        assertThat(message.body).doesNotContain("null")
    }

    @Test
    @DisplayName("language codes are parsed leniently, and unknown ones fall back to the default")
    fun languageCodeParsing() {
        assertThat(Language.fromCode("en")).isEqualTo(Language.EN)
        assertThat(Language.fromCode("EN")).isEqualTo(Language.EN)
        assertThat(Language.fromCode("en-KE")).isEqualTo(Language.EN)

        assertThat(Language.fromCode("sw")).isNull()
        assertThat(Language.fromCode("xx")).isNull()
        assertThat(Language.fromCode(null)).isNull()

        // This is the case that matters with one language configured: a client asking for
        // something unsupported must never be left with no message at all.
        assertThat(Language.fromCodeOrDefault("sw")).isEqualTo(Language.EN)
        assertThat(Language.fromCodeOrDefault("fr")).isEqualTo(Language.EN)
        assertThat(Language.fromCodeOrDefault(null)).isEqualTo(Language.EN)
    }
}
