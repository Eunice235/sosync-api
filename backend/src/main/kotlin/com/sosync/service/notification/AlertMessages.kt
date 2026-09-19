package com.sosync.service.notification

import com.sosync.common.Geo
import com.sosync.domain.Incident
import com.sosync.domain.Language
import com.sosync.domain.NotificationType
import com.sosync.domain.User

data class AlertMessage(
    val title: String,
    val body: String,
    /** Trimmed for SMS, where every character is paid for and screens are small. */
    val smsBody: String,
    /** Which catalogue produced this, so the delivery log records what the reader actually saw. */
    val language: Language,
)

/**
 * Builds the text of an alert in a given language.
 *
 * Composition lives here and wording lives in [AlertStrings], so the two can be reviewed
 * separately: a translator can check a catalogue without reading the assembly logic, and this
 * file can be changed without touching any translation.
 *
 * Every message that carries a location carries a plain maps link. A contact on a basic phone
 * with no app installed can still open it, which is the difference between an alert that
 * informs somebody and one they can act on.
 */
object AlertMessages {

    fun sosTriggered(
        incident: Incident,
        reporter: User,
        language: Language,
    ): AlertMessage {
        val s = AlertStrings.of(language)
        val where = if (hasLocation(incident)) "" else s.noLocationYet()
        val link = mapsLink(incident)

        return AlertMessage(
            title = s.sosTitle(reporter.fullName),
            body = buildString {
                append(s.sosBody(reporter.fullName))
                append(where)
                append(".")
                reporter.emergencyNote
                    ?.takeIf { it.isNotBlank() }
                    ?.let { append(s.notePrefix(it)) }
                incident.note
                    ?.takeIf { it.isNotBlank() }
                    ?.let { append(s.reporterSaidPrefix(it)) }
                link?.let { append(s.locationPrefix(it)) }
            },
            smsBody = buildString {
                append(s.smsNeedsHelp(reporter.fullName))
                append(where)
                append(". ")
                append(link ?: s.locationUnavailableSms())
                append(" ")
                append(s.doNotReply())
            },
            language = language,
        )
    }

    fun sosForResponder(
        incident: Incident,
        reporter: User,
        distanceKm: Double?,
        language: Language,
    ): AlertMessage {
        val s = AlertStrings.of(language)
        val distance = distanceKm?.let { s.distanceAway(it) } ?: ""
        val link = mapsLink(incident)

        return AlertMessage(
            title = s.responderAlertTitle(distance),
            body = buildString {
                append(s.responderAlertBody(reporter.fullName, distance))
                reporter.emergencyNote
                    ?.takeIf { it.isNotBlank() }
                    ?.let { append(s.notePrefix(it)) }
                link?.let { append(s.locationPrefix(it)) }
                append(" ")
                append(s.openAppToAccept())
            },
            smsBody = buildString {
                append(s.smsNewEmergency(distance))
                append(" ")
                append(link ?: s.locationUnavailableSms())
            },
            language = language,
        )
    }

    fun statusChange(
        incident: Incident,
        reporter: User,
        type: NotificationType,
        responderLabel: String?,
        language: Language,
    ): AlertMessage {
        val s = AlertStrings.of(language)
        val who = responderLabel ?: s.aResponder()
        val subject = reporter.fullName

        val (title, body) = when (type) {
            NotificationType.RESPONDER_ACCEPTED ->
                s.acceptedTitle() to s.acceptedBody(who, subject)

            NotificationType.RESPONDER_RESPONDING ->
                s.respondingTitle() to s.respondingBody(who, subject)

            NotificationType.RESPONDER_ARRIVED ->
                s.arrivedTitle() to s.arrivedBody(who, subject)

            NotificationType.INCIDENT_RESOLVED ->
                s.resolvedTitle() to buildString {
                    append(s.resolvedBody(subject, responderLabel))
                    append(s.sharingStopped())
                    incident.resolutionNote
                        ?.takeIf { it.isNotBlank() }
                        ?.let { append(s.notePrefix(it)) }
                }

            NotificationType.INCIDENT_CANCELLED ->
                s.cancelledTitle() to buildString {
                    append(s.cancelledBody(subject))
                    incident.cancelReason
                        ?.takeIf { it.isNotBlank() }
                        ?.let { append(s.reasonPrefix(it)) }
                    append(s.sharingStopped())
                }

            // Not reached through this path, but an exhaustive `when` beats a default branch
            // that silently produces the wrong message if a new type is added.
            NotificationType.SOS_TRIGGERED ->
                s.sosTitle(subject) to (s.sosBody(subject) + ".")
        }

        return AlertMessage(
            title = title,
            body = body,
            smsBody = "SOSYNC: $body",
            language = language,
        )
    }

    private fun hasLocation(incident: Incident): Boolean =
        Geo.isPlausible(incident.lastLat ?: incident.triggerLat, incident.lastLng ?: incident.triggerLng)

    private fun mapsLink(incident: Incident): String? {
        val lat = incident.lastLat ?: incident.triggerLat
        val lng = incident.lastLng ?: incident.triggerLng
        return if (Geo.isPlausible(lat, lng)) Geo.mapsLink(lat!!, lng!!) else null
    }
}
