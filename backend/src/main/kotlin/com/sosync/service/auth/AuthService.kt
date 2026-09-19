package com.sosync.service.auth

import com.sosync.common.BadRequestException
import com.sosync.common.ConflictException
import com.sosync.common.hash
import com.sosync.common.ForbiddenException
import com.sosync.common.NotFoundException
import com.sosync.common.Phones
import com.sosync.common.UnauthorizedException
import com.sosync.common.VerificationCodes
import com.sosync.config.SosyncProperties
import com.sosync.domain.AccountStatus
import com.sosync.domain.Language
import com.sosync.domain.Responder
import com.sosync.domain.ResponderAvailability
import com.sosync.domain.ResponderVerificationStatus
import com.sosync.domain.Role
import com.sosync.domain.User
import com.sosync.domain.VerificationCode
import com.sosync.domain.VerificationPurpose
import com.sosync.persistence.ResponderRepository
import com.sosync.persistence.UserRepository
import com.sosync.persistence.VerificationCodeRepository
import com.sosync.service.admin.AuditService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

private val log = KotlinLogging.logger {}

/** A user plus their tokens, and the code that would have been texted in a real deployment. */
data class AuthResult(
    val user: User,
    val tokens: TokenPair,
    val simulatedVerificationCode: String? = null,
)

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val responderRepository: ResponderRepository,
    private val codeRepository: VerificationCodeRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService,
    private val auditService: AuditService,
    private val properties: SosyncProperties,
) {

    /**
     * Create an account.
     *
     * Two things are deliberate here. A RESPONDER registration is created PENDING and receives
     * no alerts until an administrator verifies it, because self-service responder signup would
     * let anyone subscribe to a live feed of nearby emergencies. And ADMIN cannot be
     * self-assigned at all: the seeder creates the first one.
     *
     * Registration returns tokens immediately rather than gating on phone verification. The
     * verification code is returned in the response because there is no SMS gateway connected;
     * that is a prototype affordance and is labelled as one everywhere it appears.
     */
    @Transactional
    fun register(
        fullName: String,
        rawPhone: String,
        email: String?,
        password: String,
        requestedRole: Role?,
        organization: String?,
        preferredLanguage: Language?,
    ): AuthResult {
        val phone = Phones.normalize(rawPhone)

        if (userRepository.existsByPhone(phone)) {
            throw ConflictException("An account already exists for that phone number")
        }
        email?.takeIf { it.isNotBlank() }?.let {
            if (userRepository.existsByEmailIgnoreCase(it)) {
                throw ConflictException("An account already exists for that email address")
            }
        }

        val role = when (requestedRole) {
            null, Role.USER -> Role.USER
            Role.RESPONDER -> Role.RESPONDER
            // Refused rather than silently downgraded: quietly handing back a USER account to
            // someone who asked for ADMIN is how confusing bug reports get written.
            Role.ADMIN -> throw ForbiddenException(
                "An administrator account cannot be created through registration",
            )
        }

        if (role == Role.RESPONDER && organization.isNullOrBlank()) {
            throw BadRequestException("A responder registration needs an organization")
        }

        val user = userRepository.save(
            User(
                fullName = fullName.trim(),
                phone = phone,
                email = email?.trim()?.takeIf { it.isNotBlank() },
                passwordHash = passwordEncoder.hash(password),
                role = role,
                status = AccountStatus.ACTIVE,
                preferredLanguage = preferredLanguage ?: Language.DEFAULT,
            ),
        )

        if (role == Role.RESPONDER) {
            responderRepository.save(
                Responder(
                    userId = user.id,
                    organization = organization!!.trim(),
                    verificationStatus = ResponderVerificationStatus.PENDING,
                    availabilityStatus = ResponderAvailability.OFFLINE,
                ),
            )
            log.info { "Responder ${user.id} registered PENDING verification" }
        }

        val code = issueCode(user.id, VerificationPurpose.PHONE_VERIFICATION)

        return AuthResult(
            user = user,
            tokens = jwtService.issue(user),
            simulatedVerificationCode = code.takeIf { properties.notifications.simulate },
        )
    }

    /**
     * Sign in with phone number or email.
     *
     * Both a missing account and a wrong password answer the same way. Distinguishing them
     * confirms which numbers have accounts, and for this product that leaks who uses a personal
     * safety app.
     */
    @Transactional(readOnly = true)
    fun login(identifier: String, password: String): AuthResult {
        val user = findByIdentifier(identifier)
            ?: throw UnauthorizedException("Those details do not match an account")

        if (!passwordEncoder.matches(password, user.passwordHash)) {
            log.info { "Failed login for identifier=$identifier" }
            throw UnauthorizedException("Those details do not match an account")
        }

        if (user.status == AccountStatus.SUSPENDED) {
            throw ForbiddenException("That account is suspended. Contact support.")
        }

        return AuthResult(user, jwtService.issue(user))
    }

    @Transactional(readOnly = true)
    fun refresh(refreshToken: String): AuthResult {
        val userId = jwtService.subjectFromRefreshToken(refreshToken)
        val user = userRepository.findById(userId).orElseThrow {
            UnauthorizedException("That refresh token is not valid")
        }
        if (user.status == AccountStatus.SUSPENDED) {
            throw ForbiddenException("That account is suspended")
        }
        return AuthResult(user, jwtService.issue(user))
    }

    @Transactional
    fun verifyPhone(rawPhone: String, code: String): User {
        val phone = Phones.normalize(rawPhone)
        val user = userRepository.findByPhone(phone)
            ?: throw NotFoundException("No account for that phone number")

        consumeCode(user.id, VerificationPurpose.PHONE_VERIFICATION, code)

        user.phoneVerified = true
        user.updatedAt = Instant.now()
        return userRepository.save(user)
    }

    /**
     * Start a password reset.
     *
     * Answers the same way whether or not the number is registered, for the same reason login
     * does. The code is returned only in simulate mode, and only when the account exists.
     */
    @Transactional
    fun forgotPassword(rawPhone: String): String? {
        val phone = Phones.normalizeOrNull(rawPhone) ?: return null
        val user = userRepository.findByPhone(phone) ?: return null
        val code = issueCode(user.id, VerificationPurpose.PASSWORD_RESET)
        return code.takeIf { properties.notifications.simulate }
    }

    @Transactional
    fun resetPassword(rawPhone: String, code: String, newPassword: String): User {
        val phone = Phones.normalize(rawPhone)
        val user = userRepository.findByPhone(phone)
            ?: throw NotFoundException("No account for that phone number")

        consumeCode(user.id, VerificationPurpose.PASSWORD_RESET, code)

        user.passwordHash = passwordEncoder.hash(newPassword)
        user.updatedAt = Instant.now()
        return userRepository.save(user)
    }

    /**
     * Set or change the cancellation PIN.
     *
     * The account password is required even when only changing the PIN. Without that, somebody
     * holding an unlocked phone could set a PIN they know and then use it to cancel an active
     * emergency, which would defeat the entire purpose of having one.
     */
    @Transactional
    fun setSafetyPin(userId: String, pin: String, password: String): User {
        val user = userRepository.findById(userId).orElseThrow {
            NotFoundException("No such user")
        }

        if (!passwordEncoder.matches(password, user.passwordHash)) {
            throw ForbiddenException("That password is not correct")
        }

        user.safetyPinHash = passwordEncoder.hash(pin)
        user.updatedAt = Instant.now()
        val saved = userRepository.save(user)

        auditService.record(
            actorId = userId,
            action = AuditService.SAFETY_PIN_SET,
            targetType = "user",
            targetId = userId,
        )
        return saved
    }

    @Transactional(readOnly = true)
    fun requireUser(userId: String): User = userRepository.findById(userId).orElseThrow {
        NotFoundException("No such user")
    }

    @Transactional
    fun updateProfile(
        userId: String,
        fullName: String?,
        email: String?,
        emergencyNote: String?,
        photoUrl: String?,
        preferredLanguage: Language?,
    ): User {
        val user = requireUser(userId)

        fullName?.trim()?.takeIf { it.isNotBlank() }?.let { user.fullName = it }
        preferredLanguage?.let { user.preferredLanguage = it }
        emergencyNote?.let { user.emergencyNote = it.trim().take(500).ifBlank { null } }
        photoUrl?.let { user.photoUrl = it.trim().ifBlank { null } }

        email?.trim()?.let { candidate ->
            if (candidate.isBlank()) {
                user.email = null
            } else if (!candidate.equals(user.email, ignoreCase = true)) {
                if (userRepository.existsByEmailIgnoreCase(candidate)) {
                    throw ConflictException("Another account already uses that email address")
                }
                user.email = candidate
            }
        }

        user.updatedAt = Instant.now()
        return userRepository.save(user)
    }

    private fun findByIdentifier(identifier: String): User? {
        val trimmed = identifier.trim()
        Phones.normalizeOrNull(trimmed)?.let { phone ->
            userRepository.findByPhone(phone)?.let { return it }
        }
        return if (trimmed.contains("@")) {
            userRepository.findByEmailIgnoreCase(trimmed)
        } else {
            null
        }
    }

    private fun issueCode(userId: String, purpose: VerificationPurpose): String {
        val code = VerificationCodes.generate()
        codeRepository.save(
            VerificationCode(
                userId = userId,
                purpose = purpose,
                code = code,
                expiresAt = Instant.now().plus(CODE_LIFETIME_MINUTES, ChronoUnit.MINUTES),
            ),
        )
        log.info { "[SIMULATED] $purpose code for $userId is $code" }
        return code
    }

    private fun consumeCode(userId: String, purpose: VerificationPurpose, submitted: String) {
        val latest = codeRepository
            .findFirstByUserIdAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(userId, purpose)
            ?: throw BadRequestException("Request a new code")

        if (!latest.isUsable()) {
            throw BadRequestException("That code has expired. Request a new one.")
        }
        if (latest.code != submitted.trim()) {
            // A production build needs an attempt counter here; six digits with unlimited
            // guesses is not a control. Noted in the README as a known prototype limit.
            throw BadRequestException("That code is not correct")
        }

        latest.consumedAt = Instant.now()
        codeRepository.save(latest)
    }

    companion object {
        private const val CODE_LIFETIME_MINUTES = 15L
    }
}
