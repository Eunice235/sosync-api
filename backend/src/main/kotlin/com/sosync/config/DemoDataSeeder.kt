package com.sosync.config

import com.sosync.common.hash
import com.sosync.domain.AccountStatus
import com.sosync.domain.EmergencyContact
import com.sosync.domain.Family
import com.sosync.domain.FamilyMember
import com.sosync.domain.Incident
import com.sosync.domain.IncidentEvent
import com.sosync.domain.IncidentEventType
import com.sosync.domain.IncidentStatus
import com.sosync.domain.LocationUpdate
import com.sosync.domain.Responder
import com.sosync.domain.ResponderAvailability
import com.sosync.domain.ResponderVerificationStatus
import com.sosync.domain.Role
import com.sosync.domain.User
import com.sosync.persistence.EmergencyContactRepository
import com.sosync.persistence.FamilyMemberRepository
import com.sosync.persistence.FamilyRepository
import com.sosync.persistence.IncidentEventRepository
import com.sosync.persistence.IncidentRepository
import com.sosync.persistence.LocationUpdateRepository
import com.sosync.persistence.ResponderRepository
import com.sosync.persistence.UserRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.ApplicationRunner
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

private val log = KotlinLogging.logger {}

/**
 * Seeds the accounts and history the demo is driven from.
 *
 * Idempotent: keyed on the reporter's phone number, so restarting the service does not
 * duplicate anything and a demo can be re-run without resetting the database.
 *
 * The cast is built around one scenario, the taxi that changes route. Amina raises the alert,
 * Grace is her sister and a registered contact, a neighbour is on the list with no account at
 * all so the SMS-only path is visible, Daniel is a verified guard nearby, and Joyce is a second
 * responder left PENDING so responder verification has something to act on during the demo.
 *
 * There is also one resolved incident from three days ago, so the history and the
 * median-time-to-accept statistic are not empty on first load.
 */
@Configuration
@ConditionalOnProperty(prefix = "sosync.demo", name = ["seed"], matchIfMissing = true)
class DemoDataSeeder(
    private val userRepository: UserRepository,
    private val responderRepository: ResponderRepository,
    private val contactRepository: EmergencyContactRepository,
    private val familyRepository: FamilyRepository,
    private val familyMemberRepository: FamilyMemberRepository,
    private val incidentRepository: IncidentRepository,
    private val eventRepository: IncidentEventRepository,
    private val locationRepository: LocationUpdateRepository,
    private val passwordEncoder: PasswordEncoder,
    // The README publishes the default so a local demo just works. A server anyone can reach
    // sets its own, or every reader of the repository could sign in as the demo administrator.
    @Value("\${sosync.demo.password:$DEMO_PASSWORD}") private val demoPassword: String,
) {

    @Bean
    fun seedDemoData() = ApplicationRunner { seed() }

    @Transactional
    fun seed() {
        if (userRepository.existsByPhone(AMINA_PHONE)) {
            log.info { "Demo data already present; skipping seed" }
            return
        }

        log.info { "Seeding demo data" }

        val amina = userRepository.save(
            User(
                fullName = "Amina Wanjiru",
                phone = AMINA_PHONE,
                email = "amina@example.com",
                passwordHash = passwordEncoder.hash(demoPassword),
                role = Role.USER,
                status = AccountStatus.ACTIVE,
                // Cancelling her own alert needs this PIN, not her password.
                safetyPinHash = passwordEncoder.hash(AMINA_PIN),
                emergencyNote = "Asthmatic, carries an inhaler in her handbag. " +
                    "Usually travelling alone.",
                phoneVerified = true,
            ),
        )

        val grace = userRepository.save(
            User(
                fullName = "Grace Wanjiru",
                phone = GRACE_PHONE,
                email = "grace@example.com",
                passwordHash = passwordEncoder.hash(demoPassword),
                role = Role.USER,
                status = AccountStatus.ACTIVE,
                phoneVerified = true,
            ),
        )

        val danielAccount = userRepository.save(
            User(
                fullName = "Daniel Otieno",
                phone = DANIEL_PHONE,
                email = "daniel@example.com",
                passwordHash = passwordEncoder.hash(demoPassword),
                role = Role.RESPONDER,
                status = AccountStatus.ACTIVE,
                phoneVerified = true,
            ),
        )

        val daniel = responderRepository.save(
            Responder(
                userId = danielAccount.id,
                organization = "Nairobi Community Watch",
                verificationStatus = ResponderVerificationStatus.VERIFIED,
                availabilityStatus = ResponderAvailability.AVAILABLE,
                currentLat = DANIEL_LAT,
                currentLng = DANIEL_LNG,
                // Fresh, otherwise the matcher treats the position as stale and skips him.
                locationUpdatedAt = Instant.now(),
                verifiedAt = Instant.now().minus(30, ChronoUnit.DAYS),
            ),
        )

        val joyceAccount = userRepository.save(
            User(
                fullName = "Joyce Kamau",
                phone = JOYCE_PHONE,
                passwordHash = passwordEncoder.hash(demoPassword),
                role = Role.RESPONDER,
                status = AccountStatus.ACTIVE,
                phoneVerified = true,
            ),
        )

        // Left PENDING on purpose: she receives no alerts, and verifying her is a one-click
        // demonstration of the trust boundary from the admin surface.
        responderRepository.save(
            Responder(
                userId = joyceAccount.id,
                organization = "Westlands Security Services",
                verificationStatus = ResponderVerificationStatus.PENDING,
                availabilityStatus = ResponderAvailability.OFFLINE,
            ),
        )

        userRepository.save(
            User(
                fullName = "SOSync Operator",
                phone = ADMIN_PHONE,
                email = "admin@sosync.app",
                passwordHash = passwordEncoder.hash(demoPassword),
                role = Role.ADMIN,
                status = AccountStatus.ACTIVE,
                phoneVerified = true,
            ),
        )

        // Grace has an account, so she gets SMS, push and in-app, and can follow the incident.
        contactRepository.save(
            EmergencyContact(
                userId = amina.id,
                name = "Grace (sister)",
                phone = grace.phone,
                relationship = "Sister",
                priority = 1,
            ),
        )

        // This number has no account anywhere. It exists so the demo shows the SMS-only path,
        // which is the case that matters most in practice.
        contactRepository.save(
            EmergencyContact(
                userId = amina.id,
                name = "John (neighbour)",
                phone = NEIGHBOUR_PHONE,
                relationship = "Neighbour",
                priority = 2,
            ),
        )

        val family = familyRepository.save(
            Family(ownerUserId = amina.id, name = "Wanjiru household"),
        )
        familyMemberRepository.save(
            FamilyMember(familyId = family.id, userId = amina.id, relationship = "Owner"),
        )
        familyMemberRepository.save(
            FamilyMember(familyId = family.id, userId = grace.id, relationship = "Sister"),
        )

        seedResolvedIncident(amina, daniel.id, danielAccount.fullName)

        log.info {
            "Demo data seeded. Sign in with $AMINA_PHONE and the demo password " +
                "(safety PIN $AMINA_PIN)"
        }
    }

    /**
     * One closed incident from three days ago.
     *
     * Present so that incident history, the delivery log and the median-time-to-accept
     * statistic all have something in them on first load. An admin dashboard whose every number
     * reads zero is hard to tell apart from one that is broken.
     */
    private fun seedResolvedIncident(
        reporter: User,
        responderId: String,
        responderLabel: String,
    ) {
        val triggeredAt = Instant.now().minus(3, ChronoUnit.DAYS)
        val acceptedAt = triggeredAt.plusSeconds(74)
        val arrivedAt = triggeredAt.plusSeconds(402)
        val resolvedAt = triggeredAt.plusSeconds(631)

        val incident = incidentRepository.save(
            Incident(
                userId = reporter.id,
                status = IncidentStatus.RESOLVED,
                silent = true,
                note = "Followed on foot after leaving the matatu stage.",
                triggerLat = HISTORIC_LAT,
                triggerLng = HISTORIC_LNG,
                triggerAccuracy = 18.0,
                lastLat = HISTORIC_LAT + 0.0019,
                lastLng = HISTORIC_LNG + 0.0012,
                lastLocationAt = arrivedAt,
                assignedResponderId = responderId,
                resolutionNote = "Reporter reached home safely. Walked with her from the stage.",
                triggeredAt = triggeredAt,
                acceptedAt = acceptedAt,
                arrivedAt = arrivedAt,
                closedAt = resolvedAt,
                updatedAt = resolvedAt,
            ),
        )

        // A short trail, so the map has a path rather than a single pin.
        listOf(
            Triple(HISTORIC_LAT, HISTORIC_LNG, triggeredAt),
            Triple(HISTORIC_LAT + 0.0008, HISTORIC_LNG + 0.0005, triggeredAt.plusSeconds(120)),
            Triple(HISTORIC_LAT + 0.0014, HISTORIC_LNG + 0.0009, triggeredAt.plusSeconds(260)),
            Triple(HISTORIC_LAT + 0.0019, HISTORIC_LNG + 0.0012, arrivedAt),
        ).forEach { (lat, lng, at) ->
            locationRepository.save(
                LocationUpdate(
                    incidentId = incident.id,
                    latitude = lat,
                    longitude = lng,
                    accuracy = 18.0,
                    recordedAt = at,
                ),
            )
        }

        listOf(
            IncidentEvent(
                incidentId = incident.id,
                eventType = IncidentEventType.TRIGGERED,
                actorId = reporter.id,
                actorLabel = reporter.fullName,
                notes = "SOS raised with GPS fix",
                occurredAt = triggeredAt,
            ),
            IncidentEvent(
                incidentId = incident.id,
                eventType = IncidentEventType.NOTIFIED,
                actorLabel = "SOSync",
                notes = "6 deliveries to 3 recipients",
                occurredAt = triggeredAt.plusSeconds(2),
            ),
            IncidentEvent(
                incidentId = incident.id,
                eventType = IncidentEventType.ACKNOWLEDGED,
                actorLabel = "Grace (sister)",
                notes = "Alert seen",
                occurredAt = triggeredAt.plusSeconds(41),
            ),
            IncidentEvent(
                incidentId = incident.id,
                eventType = IncidentEventType.ACCEPTED,
                actorLabel = responderLabel,
                notes = "Accepted by Nairobi Community Watch",
                occurredAt = acceptedAt,
            ),
            IncidentEvent(
                incidentId = incident.id,
                eventType = IncidentEventType.RESPONDING,
                actorLabel = responderLabel,
                notes = "On the way",
                occurredAt = acceptedAt.plusSeconds(15),
            ),
            IncidentEvent(
                incidentId = incident.id,
                eventType = IncidentEventType.ARRIVED,
                actorLabel = responderLabel,
                occurredAt = arrivedAt,
            ),
            IncidentEvent(
                incidentId = incident.id,
                eventType = IncidentEventType.RESOLVED,
                actorLabel = responderLabel,
                notes = "Reporter reached home safely",
                occurredAt = resolvedAt,
            ),
        ).forEach { eventRepository.save(it) }
    }

    companion object {
        const val DEMO_PASSWORD = "Sosync#2026"
        const val AMINA_PIN = "4821"

        const val AMINA_PHONE = "+254712000001"
        const val GRACE_PHONE = "+254712000002"
        const val DANIEL_PHONE = "+254712000003"
        const val JOYCE_PHONE = "+254712000004"
        const val ADMIN_PHONE = "+254712000009"

        /** No account attached, so this contact demonstrates the SMS-only path. */
        const val NEIGHBOUR_PHONE = "+254712000099"

        // Nairobi. Daniel sits about 1 km from where the live demo incident is raised, which
        // puts him inside the dispatch radius.
        const val DANIEL_LAT = -1.2833
        const val DANIEL_LNG = 36.8172

        const val HISTORIC_LAT = -1.2921
        const val HISTORIC_LNG = 36.8219
    }
}
