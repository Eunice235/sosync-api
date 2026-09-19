package com.sosync.support

import com.sosync.common.hash
import com.sosync.domain.AccountStatus
import com.sosync.domain.EmergencyContact
import com.sosync.domain.Family
import com.sosync.domain.FamilyMember
import com.sosync.domain.Language
import com.sosync.domain.Responder
import com.sosync.domain.ResponderAvailability
import com.sosync.domain.ResponderVerificationStatus
import com.sosync.domain.Role
import com.sosync.domain.User
import com.sosync.persistence.EmergencyContactRepository
import com.sosync.persistence.FamilyMemberRepository
import com.sosync.persistence.FamilyRepository
import com.sosync.persistence.ResponderRepository
import com.sosync.persistence.UserRepository
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Builders for test data.
 *
 * Every helper takes the few things a test actually cares about and picks sensible values for
 * the rest, so a test reads as the rule it is checking rather than as twenty lines of setup.
 */
@Component
class TestFixtures(
    private val userRepository: UserRepository,
    private val responderRepository: ResponderRepository,
    private val contactRepository: EmergencyContactRepository,
    private val familyRepository: FamilyRepository,
    private val familyMemberRepository: FamilyMemberRepository,
    private val passwordEncoder: PasswordEncoder,
) {
    /** Unique phone numbers, since the column is unique and tests create several accounts. */
    private val sequence = AtomicInteger(1000)

    fun nextPhone(): String = "+2547${sequence.incrementAndGet().toString().padStart(8, '0')}"

    fun user(
        name: String = "Test User",
        phone: String = nextPhone(),
        password: String = PASSWORD,
        safetyPin: String? = PIN,
        language: Language = Language.EN,
        role: Role = Role.USER,
        emergencyNote: String? = null,
        status: AccountStatus = AccountStatus.ACTIVE,
    ): User = userRepository.save(
        User(
            fullName = name,
            phone = phone,
            passwordHash = passwordEncoder.hash(password),
            role = role,
            status = status,
            safetyPinHash = safetyPin?.let { passwordEncoder.hash(it) },
            preferredLanguage = language,
            emergencyNote = emergencyNote,
            phoneVerified = true,
        ),
    )

    /**
     * A responder account plus its responder row. Verified and available by default, because
     * that is the only configuration that receives alerts and most tests want one that does.
     */
    fun responder(
        name: String = "Test Responder",
        organization: String = "Test Security",
        verification: ResponderVerificationStatus = ResponderVerificationStatus.VERIFIED,
        availability: ResponderAvailability = ResponderAvailability.AVAILABLE,
        lat: Double? = NAIROBI_LAT,
        lng: Double? = NAIROBI_LNG,
        locationFresh: Boolean = true,
        language: Language = Language.EN,
    ): Pair<User, Responder> {
        val account = user(name = name, role = Role.RESPONDER, language = language)
        val responder = responderRepository.save(
            Responder(
                userId = account.id,
                organization = organization,
                verificationStatus = verification,
                availabilityStatus = availability,
                currentLat = lat,
                currentLng = lng,
                // A stale position is excluded from matching, so tests that want to be found
                // need a recent one.
                locationUpdatedAt = if (lat != null && locationFresh) Instant.now() else null,
                verifiedAt = if (verification == ResponderVerificationStatus.VERIFIED) {
                    Instant.now()
                } else {
                    null
                },
            ),
        )
        return account to responder
    }

    fun contact(
        owner: User,
        name: String = "Trusted Person",
        phone: String = nextPhone(),
        priority: Int = 1,
        enabled: Boolean = true,
        language: Language? = null,
    ): EmergencyContact = contactRepository.save(
        EmergencyContact(
            userId = owner.id,
            name = name,
            phone = phone,
            priority = priority,
            notificationEnabled = enabled,
            language = language,
        ),
    )

    /** Puts two accounts in the same household, which is a second route for an alert. */
    fun family(owner: User, vararg members: User): Family {
        val family = familyRepository.save(
            Family(ownerUserId = owner.id, name = "Test Household"),
        )
        familyMemberRepository.save(FamilyMember(familyId = family.id, userId = owner.id))
        members.forEach {
            familyMemberRepository.save(FamilyMember(familyId = family.id, userId = it.id))
        }
        return family
    }

    companion object {
        const val PASSWORD = "TestPass#2026"
        const val PIN = "4821"

        // Nairobi CBD. Responders created by these fixtures sit at the same point, so they are
        // always inside the dispatch radius of an incident raised there.
        const val NAIROBI_LAT = -1.2921
        const val NAIROBI_LNG = 36.8219
    }
}
