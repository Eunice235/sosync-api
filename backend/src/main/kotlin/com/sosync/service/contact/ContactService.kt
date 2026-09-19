package com.sosync.service.contact

import com.sosync.common.ConflictException
import com.sosync.common.NotFoundException
import com.sosync.common.Phones
import com.sosync.domain.EmergencyContact
import com.sosync.domain.Language
import com.sosync.persistence.EmergencyContactRepository
import com.sosync.persistence.UserRepository
import com.sosync.web.dto.ContactResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * The trusted-contact list.
 *
 * Contacts are keyed by phone number, which is what makes the whole arrangement work for people
 * who have never heard of SOSync: the number gets an SMS regardless, and if it happens to belong
 * to an account, that account also gets in-app alerts and can follow the incident. Nobody has to
 * accept an invitation before they can be relied on in an emergency.
 */
@Service
class ContactService(
    private val contactRepository: EmergencyContactRepository,
    private val userRepository: UserRepository,
) {

    @Transactional(readOnly = true)
    fun list(userId: String): List<ContactResponse> {
        val contacts = contactRepository.findAllByUserIdOrderByPriorityAscCreatedAtAsc(userId)
        if (contacts.isEmpty()) return emptyList()

        // One query to find which of these numbers have accounts, not one per contact.
        val registered = userRepository
            .findAllByPhoneIn(contacts.map { it.phone })
            .map { it.phone }
            .toSet()

        return contacts.map { ContactResponse.from(it, it.phone in registered) }
    }

    @Transactional
    fun add(
        userId: String,
        name: String,
        rawPhone: String,
        relationship: String?,
        priority: Int,
        notificationEnabled: Boolean,
        language: Language?,
    ): ContactResponse {
        val phone = Phones.normalize(rawPhone)

        val owner = userRepository.findById(userId).orElseThrow {
            NotFoundException("No such user")
        }
        if (phone == owner.phone) {
            throw ConflictException("You cannot add your own number as a trusted contact")
        }
        if (contactRepository.existsByUserIdAndPhone(userId, phone)) {
            throw ConflictException("That number is already one of your trusted contacts")
        }
        if (contactRepository.countByUserId(userId) >= MAX_CONTACTS) {
            throw ConflictException("You can have at most $MAX_CONTACTS trusted contacts")
        }

        val saved = contactRepository.save(
            EmergencyContact(
                userId = userId,
                name = name.trim(),
                phone = phone,
                relationship = relationship?.trim()?.ifBlank { null },
                priority = priority,
                notificationEnabled = notificationEnabled,
                language = language,
            ),
        )

        return ContactResponse.from(saved, userRepository.findByPhone(phone) != null)
    }

    @Transactional
    fun update(
        userId: String,
        contactId: String,
        name: String,
        rawPhone: String,
        relationship: String?,
        priority: Int,
        notificationEnabled: Boolean,
        language: Language?,
    ): ContactResponse {
        val contact = contactRepository.findByIdAndUserId(contactId, userId)
            ?: throw NotFoundException("No such contact")

        val phone = Phones.normalize(rawPhone)
        if (phone != contact.phone && contactRepository.existsByUserIdAndPhone(userId, phone)) {
            throw ConflictException("That number is already one of your trusted contacts")
        }

        contact.name = name.trim()
        contact.phone = phone
        contact.relationship = relationship?.trim()?.ifBlank { null }
        contact.priority = priority
        contact.notificationEnabled = notificationEnabled
        contact.language = language
        contact.updatedAt = Instant.now()

        val saved = contactRepository.save(contact)
        return ContactResponse.from(saved, userRepository.findByPhone(phone) != null)
    }

    /**
     * Remove a contact.
     *
     * Hard delete, unlike the soft deletes used elsewhere in the Readers conventions. Nothing
     * references a contact row after the fact: the notifications already sent keep their own
     * copy of the recipient name and number, so the history of an incident stays intact while
     * the person genuinely stops being on the list.
     */
    @Transactional
    fun remove(userId: String, contactId: String) {
        val contact = contactRepository.findByIdAndUserId(contactId, userId)
            ?: throw NotFoundException("No such contact")
        contactRepository.delete(contact)
    }

    companion object {
        /**
         * A cap exists so one account cannot turn an SOS into a bulk SMS broadcast. Ten is well
         * past what anyone genuinely relies on.
         */
        const val MAX_CONTACTS = 10
    }
}
