package com.sosync.service.notification

import com.sosync.domain.Language

/**
 * Every piece of wording that leaves the service addressed to a person.
 *
 * English only. Kept as an interface with one implementation rather than inlined, so the wording
 * stays in one reviewable place and a second language is a new class rather than a hunt through
 * the fan-out logic. The compile-time completeness that buys - a new language will not build
 * until every message exists - is why the shape is worth keeping with a single entry in it.
 *
 * Two constraints shape the wording itself:
 *
 * - **SMS bodies stay short.** They are paid for per segment and read on small screens, often
 *   in a hurry. Every `sms*` string here is written to leave room for a name and a maps link
 *   inside a single 160-character segment wherever possible.
 * - **Every message names who and where.** A notification that says only "emergency" leaves the
 *   recipient with nothing to act on.
 */
interface AlertStrings {

    val language: Language

    // ── The initial alert, to a trusted contact or family member ────────────────────────

    fun sosTitle(reporterName: String): String
    fun sosBody(reporterName: String): String
    fun smsNeedsHelp(reporterName: String): String

    // ── The initial alert, to a responder ───────────────────────────────────────────────

    fun responderAlertTitle(distanceText: String): String
    fun responderAlertBody(reporterName: String, distanceText: String): String
    fun openAppToAccept(): String
    fun smsNewEmergency(distanceText: String): String

    // ── Progress updates ────────────────────────────────────────────────────────────────

    fun acceptedTitle(): String
    fun acceptedBody(responderName: String, reporterName: String): String
    fun respondingTitle(): String
    fun respondingBody(responderName: String, reporterName: String): String
    fun arrivedTitle(): String
    fun arrivedBody(responderName: String, reporterName: String): String
    fun resolvedTitle(): String
    fun resolvedBody(reporterName: String, responderName: String?): String
    fun cancelledTitle(): String
    fun cancelledBody(reporterName: String): String

    // ── Fragments appended to the above ─────────────────────────────────────────────────

    /** Used when no GPS fix has arrived yet, so the recipient is not left guessing. */
    fun noLocationYet(): String
    fun locationUnavailableSms(): String
    fun locationPrefix(link: String): String
    fun notePrefix(note: String): String
    fun reporterSaidPrefix(note: String): String
    fun reasonPrefix(reason: String): String
    fun sharingStopped(): String
    fun doNotReply(): String

    /** Stand-in when a responder organisation name is not available. */
    fun aResponder(): String

    fun distanceAway(km: Double): String

    // ── Shown to the reporter immediately after pressing the button ─────────────────────

    fun sosSummaryAlreadyActive(): String
    fun sosSummaryNobodyReached(): String
    fun sosSummarySent(recipientCount: Int): String

    companion object {
        /**
         * The catalogue for a language. Exhaustive `when` on the enum, so a new [Language]
         * constant will not compile until its catalogue exists.
         */
        fun of(language: Language): AlertStrings = when (language) {
            Language.EN -> EnglishAlertStrings
        }
    }
}

object EnglishAlertStrings : AlertStrings {

    override val language = Language.EN

    override fun sosTitle(reporterName: String) = "SOS from $reporterName"

    override fun sosBody(reporterName: String) =
        "$reporterName has raised an emergency alert"

    override fun smsNeedsHelp(reporterName: String) = "SOSYNC: $reporterName needs help"

    override fun responderAlertTitle(distanceText: String) = "New emergency$distanceText"

    override fun responderAlertBody(reporterName: String, distanceText: String) =
        "$reporterName raised an SOS$distanceText."

    override fun openAppToAccept() = "Open SOSync to accept."

    override fun smsNewEmergency(distanceText: String) = "SOSYNC: new emergency$distanceText."

    override fun acceptedTitle() = "Help is on the way"

    override fun acceptedBody(responderName: String, reporterName: String) =
        "$responderName accepted the alert for $reporterName and is responding."

    override fun respondingTitle() = "Responder en route"

    override fun respondingBody(responderName: String, reporterName: String) =
        "$responderName is travelling to $reporterName now."

    override fun arrivedTitle() = "Responder has arrived"

    override fun arrivedBody(responderName: String, reporterName: String) =
        "$responderName has reached $reporterName."

    override fun resolvedTitle() = "Emergency resolved"

    override fun resolvedBody(reporterName: String, responderName: String?) = buildString {
        append("The emergency for $reporterName has been marked resolved")
        responderName?.let { append(" by $it") }
        append(".")
    }

    override fun cancelledTitle() = "Alert cancelled"

    override fun cancelledBody(reporterName: String) =
        "$reporterName cancelled the emergency alert."

    override fun noLocationYet() = " (no location fix yet)"

    override fun locationUnavailableSms() = "Location not yet available."

    override fun locationPrefix(link: String) = " Location: $link"

    override fun notePrefix(note: String) = " Note: $note"

    override fun reporterSaidPrefix(note: String) = " Said: $note"

    override fun reasonPrefix(reason: String) = " Reason: $reason"

    override fun sharingStopped() = " Live location sharing has stopped."

    override fun doNotReply() = "Do not reply to this message."

    override fun aResponder() = "A responder"

    override fun distanceAway(km: Double) = " ${km}km away"

    override fun sosSummaryAlreadyActive() =
        "You already have an emergency in progress. Help is still coming."

    override fun sosSummaryNobodyReached() =
        "Alert raised, but nobody could be reached. Add a trusted contact so the next one " +
            "gets through."

    override fun sosSummarySent(recipientCount: Int) =
        "Alert sent to $recipientCount " +
            (if (recipientCount == 1) "person" else "people") +
            ". Your location will keep updating."
}
