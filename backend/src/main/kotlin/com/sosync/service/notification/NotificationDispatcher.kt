package com.sosync.service.notification

import com.sosync.domain.DeliveryStatus
import com.sosync.domain.EmergencyContact
import com.sosync.domain.Incident
import com.sosync.domain.Language
import com.sosync.domain.NotificationChannel
import com.sosync.domain.NotificationRecord
import com.sosync.domain.NotificationType
import com.sosync.domain.User
import com.sosync.persistence.EmergencyContactRepository
import com.sosync.persistence.FamilyMemberRepository
import com.sosync.persistence.NotificationRepository
import com.sosync.persistence.ResponderRepository
import com.sosync.persistence.UserRepository
import com.sosync.service.responder.ResponderMatcher
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

private val log = KotlinLogging.logger {}

/**
 * Turns an incident into messages, and messages into delivery attempts.
 *
 * Every attempt is written to `notifications` before the gateway is called, including the
 * IN_APP copy and the language it went out in. The tray a recipient opens is therefore exactly
 * the same data the SMS and push gateways were handed, which makes "who was told, in what
 * language, on what channel, and did it arrive" answerable after the fact instead of a guess.
 *
 * Messages are built per recipient rather than once per incident, because the recipients of one
 * alert do not necessarily share a language. That costs a few string builds and is the whole
 * point of the feature.
 *
 * Fan-out is synchronous, inside the caller's transaction. With simulated gateways that costs
 * microseconds and buys a useful property: the API response to the SOS trigger already contains
 * the full recipient list, so the app can show the reporter who was reached. A real SMS gateway
 * takes about a second per recipient, and at that point delivery has to move to a background
 * worker draining the PENDING rows: the row is written in the transaction, the send happens
 * outside it. That is the one change needed here to go to production.
 */
@Service
class NotificationDispatcher(
    private val notificationRepository: NotificationRepository,
    private val contactRepository: EmergencyContactRepository,
    private val userRepository: UserRepository,
    private val responderRepository: ResponderRepository,
    private val familyMemberRepository: FamilyMemberRepository,
    private val responderMatcher: ResponderMatcher,
    private val smsGateway: SmsGateway,
    private val pushGateway: PushGateway,
) {

    /**
     * The initial fan-out. Reaches, in this order of priority:
     *
     * - trusted contacts, by SMS and, where the number belongs to an account, in-app and push
     * - family members, in-app and push
     * - nearby verified responders, in-app and push
     */
    @Transactional
    fun dispatchSosTriggered(incident: Incident, reporter: User): List<NotificationRecord> {
        val records = mutableListOf<NotificationRecord>()

        val contacts = contactRepository
            .findAllByUserIdAndNotificationEnabledTrueOrderByPriorityAsc(incident.userId)

        // One query for every contact phone that happens to be a registered account, rather
        // than a lookup per contact.
        val accountsByPhone = if (contacts.isEmpty()) {
            emptyMap()
        } else {
            userRepository.findAllByPhoneIn(contacts.map { it.phone }).associateBy { it.phone }
        }

        if (contacts.isEmpty()) {
            log.warn {
                "Incident ${incident.id} raised by ${incident.userId} who has no trusted " +
                    "contacts configured"
            }
        }

        contacts.forEach { contact ->
            val account = accountsByPhone[contact.phone]
            val language = languageFor(contact, account, reporter)
            val message = AlertMessages.sosTriggered(incident, reporter, language)

            // SMS goes to every enabled contact. It is the only channel that works on a feature
            // phone, with no app installed, and with no data bundle left.
            records += deliverSms(
                incident = incident,
                type = NotificationType.SOS_TRIGGERED,
                phone = contact.phone,
                label = contact.name,
                recipientUserId = account?.id,
                message = message,
            )

            if (account != null) {
                records += deliverInApp(
                    incident, NotificationType.SOS_TRIGGERED, account, contact.name, message,
                )
                records += deliverPush(
                    incident, NotificationType.SOS_TRIGGERED, account, contact.name, message,
                )
            }
        }

        // Family members are alerted without needing to also appear on the contact list.
        familyMembersOf(incident.userId)
            .filter { it.id != incident.userId }
            .filter { member -> contacts.none { it.phone == member.phone } }
            .forEach { member ->
                val message = AlertMessages.sosTriggered(
                    incident, reporter, member.preferredLanguage,
                )
                records += deliverInApp(
                    incident, NotificationType.SOS_TRIGGERED, member, member.fullName, message,
                )
                records += deliverPush(
                    incident, NotificationType.SOS_TRIGGERED, member, member.fullName, message,
                )
            }

        val matched = responderMatcher.matchFor(incident)
        if (matched.isEmpty()) {
            log.warn { "Incident ${incident.id} matched no available verified responder" }
        }

        val responderUsers = userRepository
            .findAllByIdIn(matched.map { it.responder.userId })
            .associateBy { it.id }

        matched.forEach { match ->
            val account = responderUsers[match.responder.userId] ?: return@forEach
            val message = AlertMessages.sosForResponder(
                incident, reporter, match.distanceKm, account.preferredLanguage,
            )
            records += deliverInApp(
                incident,
                NotificationType.SOS_TRIGGERED,
                account,
                match.responder.organization,
                message,
            )
            records += deliverPush(
                incident,
                NotificationType.SOS_TRIGGERED,
                account,
                match.responder.organization,
                message,
            )
        }

        log.info {
            "Incident ${incident.id}: ${records.size} deliveries to " +
                "${contacts.size} contacts and ${matched.size} responders " +
                "in ${records.map { it.language }.distinct().joinToString(",")}"
        }
        return records
    }

    /**
     * Status updates go back the other way: to the reporter, and to everyone who was told about
     * the emergency in the first place, so nobody is left watching a stale alert.
     */
    @Transactional
    fun dispatchStatusChange(
        incident: Incident,
        reporter: User,
        type: NotificationType,
        responderLabel: String?,
    ): List<NotificationRecord> {
        val records = mutableListOf<NotificationRecord>()

        // The reporter hears about it in-app only. A silent incident must stay silent on the
        // reporting device, and an SMS arriving mid-emergency is exactly the visible, audible
        // thing the whole design exists to avoid.
        records += deliverInApp(
            incident,
            type,
            reporter,
            reporter.fullName,
            AlertMessages.statusChange(
                incident, reporter, type, responderLabel, reporter.preferredLanguage,
            ),
        )

        val contacts = contactRepository
            .findAllByUserIdAndNotificationEnabledTrueOrderByPriorityAsc(incident.userId)

        val accountsByPhone = if (contacts.isEmpty()) {
            emptyMap()
        } else {
            userRepository.findAllByPhoneIn(contacts.map { it.phone }).associateBy { it.phone }
        }

        contacts.forEach { contact ->
            val account = accountsByPhone[contact.phone]
            val language = languageFor(contact, account, reporter)
            val message = AlertMessages.statusChange(
                incident, reporter, type, responderLabel, language,
            )

            if (account != null) {
                records += deliverInApp(incident, type, account, contact.name, message)
                records += deliverPush(incident, type, account, contact.name, message)
            } else if (type in SMS_WORTHY_UPDATES) {
                // No account, so in-app is impossible; SMS is the only way they learn the
                // outcome. Resolution and cancellation are worth a text, intermediate progress
                // is not.
                records += deliverSms(
                    incident = incident,
                    type = type,
                    phone = contact.phone,
                    label = contact.name,
                    recipientUserId = null,
                    message = message,
                )
            }
        }

        // The assigned responder is told when the reporter stands the incident down, so they
        // stop travelling to it.
        if (type in SMS_WORTHY_UPDATES) {
            incident.assignedResponderId
                ?.let { responderRepository.findById(it).orElse(null) }
                ?.let { responder -> userRepository.findById(responder.userId).orElse(null) }
                ?.let { account ->
                    val message = AlertMessages.statusChange(
                        incident, reporter, type, responderLabel, account.preferredLanguage,
                    )
                    records += deliverInApp(incident, type, account, account.fullName, message)
                    records += deliverPush(incident, type, account, account.fullName, message)
                }
        }

        return records
    }

    /**
     * Which language a contact reads.
     *
     * A registered account's own preference wins, because that is the person stating it about
     * themselves. The per-contact override is next, for a contact with no account whose
     * language the reporter had to tell us. The reporter's own language is the last resort: it
     * is a guess, but a household or neighbour usually shares it, and it beats defaulting
     * everyone to English.
     */
    private fun languageFor(
        contact: EmergencyContact,
        account: User?,
        reporter: User,
    ): Language = account?.preferredLanguage
        ?: contact.language
        ?: reporter.preferredLanguage

    private fun familyMembersOf(userId: String): List<User> {
        val memberships = familyMemberRepository.findAllByUserId(userId)
        if (memberships.isEmpty()) return emptyList()

        val peerIds = memberships
            .flatMap { familyMemberRepository.findAllByFamilyId(it.familyId) }
            .map { it.userId }
            .distinct()
            .filter { it != userId }

        return if (peerIds.isEmpty()) emptyList() else userRepository.findAllByIdIn(peerIds)
    }

    private fun deliverSms(
        incident: Incident,
        type: NotificationType,
        phone: String,
        label: String,
        recipientUserId: String?,
        message: AlertMessage,
    ): NotificationRecord {
        val record = NotificationRecord(
            incidentId = incident.id,
            recipientUserId = recipientUserId,
            recipientPhone = phone,
            recipientLabel = label,
            type = type,
            channel = NotificationChannel.SMS,
            title = message.title,
            body = message.smsBody,
            language = message.language,
        )
        val outcome = smsGateway.send(phone, message.smsBody)
        record.deliveryStatus = outcome.status
        record.failureReason = outcome.failureReason
        return notificationRepository.save(record)
    }

    private fun deliverPush(
        incident: Incident,
        type: NotificationType,
        recipient: User,
        label: String,
        message: AlertMessage,
    ): NotificationRecord {
        val record = NotificationRecord(
            incidentId = incident.id,
            recipientUserId = recipient.id,
            recipientPhone = recipient.phone,
            recipientLabel = label,
            type = type,
            channel = NotificationChannel.PUSH,
            title = message.title,
            body = message.body,
            language = message.language,
        )
        val outcome = pushGateway.send(recipient.id, message.title, message.body)
        record.deliveryStatus = outcome.status
        record.failureReason = outcome.failureReason
        return notificationRepository.save(record)
    }

    /**
     * The in-app copy. Written with status SENT without calling any gateway, because storing
     * the row *is* the delivery: the recipient reads it from the tray on next fetch.
     */
    private fun deliverInApp(
        incident: Incident,
        type: NotificationType,
        recipient: User,
        label: String,
        message: AlertMessage,
    ): NotificationRecord = notificationRepository.save(
        NotificationRecord(
            incidentId = incident.id,
            recipientUserId = recipient.id,
            recipientPhone = recipient.phone,
            recipientLabel = label,
            type = type,
            channel = NotificationChannel.IN_APP,
            deliveryStatus = DeliveryStatus.SENT,
            title = message.title,
            body = message.body,
            language = message.language,
        ),
    )

    companion object {
        /** Updates that justify spending an SMS on a contact who has no account. */
        private val SMS_WORTHY_UPDATES = setOf(
            NotificationType.RESPONDER_ACCEPTED,
            NotificationType.INCIDENT_RESOLVED,
            NotificationType.INCIDENT_CANCELLED,
        )
    }
}
