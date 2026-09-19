package com.sosync.service.family

import com.sosync.common.ConflictException
import com.sosync.common.ForbiddenException
import com.sosync.common.NotFoundException
import com.sosync.common.Phones
import com.sosync.domain.Family
import com.sosync.domain.FamilyMember
import com.sosync.domain.PlanType
import com.sosync.domain.Subscription
import com.sosync.domain.SubscriptionStatus
import com.sosync.persistence.FamilyMemberRepository
import com.sosync.persistence.FamilyRepository
import com.sosync.persistence.SubscriptionRepository
import com.sosync.persistence.UserRepository
import com.sosync.web.dto.FamilyMemberResponse
import com.sosync.web.dto.FamilyResponse
import com.sosync.web.dto.PlanResponse
import com.sosync.web.dto.SubscriptionResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * Households, and the simulated plan attached to one.
 *
 * Family membership is a second route for an alert to reach someone: members hear about each
 * other's emergencies without each having to nominate the others as individual contacts. That
 * is the whole feature. Household administration beyond it is deferred, as the project template
 * marks this MVP-LITE.
 */
@Service
class FamilyService(
    private val familyRepository: FamilyRepository,
    private val memberRepository: FamilyMemberRepository,
    private val userRepository: UserRepository,
    private val subscriptionRepository: SubscriptionRepository,
) {

    @Transactional
    fun create(ownerId: String, name: String): FamilyResponse {
        familyRepository.findByOwnerUserId(ownerId)?.let {
            throw ConflictException("You already have a family group")
        }

        val family = familyRepository.save(Family(ownerUserId = ownerId, name = name.trim()))

        // The owner is a member of their own group, so alert fan-out needs no special case for
        // them.
        memberRepository.save(
            FamilyMember(familyId = family.id, userId = ownerId, relationship = "Owner"),
        )

        return describe(family)
    }

    /** Groups the caller owns or belongs to. */
    @Transactional(readOnly = true)
    fun forUser(userId: String): List<FamilyResponse> {
        val familyIds = memberRepository.findAllByUserId(userId).map { it.familyId }.distinct()
        if (familyIds.isEmpty()) return emptyList()
        return familyRepository.findAllById(familyIds).map { describe(it) }
    }

    /**
     * Add a member by phone number.
     *
     * Unlike a trusted contact, this requires an existing account: family membership is mutual
     * visibility of emergencies, and that is not something to confer on a phone number whose
     * owner has not signed up and agreed to it.
     */
    @Transactional
    fun addMember(
        ownerId: String,
        familyId: String,
        rawPhone: String,
        relationship: String?,
    ): FamilyResponse {
        val family = requireOwnedFamily(familyId, ownerId)
        val phone = Phones.normalize(rawPhone)

        val member = userRepository.findByPhone(phone)
            ?: throw NotFoundException(
                "Nobody is registered with that number yet. Ask them to create an " +
                    "SOSync account first, or add them as a trusted contact instead.",
            )

        if (memberRepository.existsByFamilyIdAndUserId(familyId, member.id)) {
            throw ConflictException("${member.fullName} is already in this group")
        }
        if (memberRepository.findAllByFamilyId(familyId).size >= MAX_MEMBERS) {
            throw ConflictException("A family group holds at most $MAX_MEMBERS people")
        }

        memberRepository.save(
            FamilyMember(
                familyId = familyId,
                userId = member.id,
                relationship = relationship?.trim()?.ifBlank { null },
            ),
        )

        return describe(family)
    }

    @Transactional
    fun removeMember(ownerId: String, familyId: String, memberUserId: String): FamilyResponse {
        val family = requireOwnedFamily(familyId, ownerId)

        if (memberUserId == ownerId) {
            throw ConflictException("The owner cannot be removed from their own group")
        }

        val member = memberRepository.findByFamilyIdAndUserId(familyId, memberUserId)
            ?: throw NotFoundException("That person is not in this group")

        memberRepository.delete(member)
        return describe(family)
    }

    // ── Plans ───────────────────────────────────────────────────────────────────────

    /**
     * The plan catalogue.
     *
     * Static, and priced only to show the commercial shape. Note what is not listed as a paid
     * feature: the SOS button, location sharing and responder dispatch are on every tier,
     * including none. Putting an emergency behind a paywall is a decision that has to be made
     * openly, not one a prototype should imply.
     */
    fun plans(): List<PlanResponse> = listOf(
        PlanResponse(
            planType = PlanType.INDIVIDUAL,
            name = "Individual",
            priceMonthly = "KES 150 / month",
            features = listOf(
                "SOS alerts to up to 10 trusted contacts",
                "Live location for the duration of an emergency",
                "Dispatch to verified nearby responders",
                "Full incident timeline and delivery log",
            ),
        ),
        PlanResponse(
            planType = PlanType.FAMILY,
            name = "Family",
            priceMonthly = "KES 400 / month",
            features = listOf(
                "Everything in Individual, for up to 6 people",
                "Household members alerted for each other automatically",
                "Shared view of any open emergency in the group",
            ),
        ),
    )

    @Transactional(readOnly = true)
    fun subscriptionFor(userId: String): SubscriptionResponse? =
        subscriptionRepository.findByOwnerUserId(userId)?.let { SubscriptionResponse.from(it) }

    /**
     * Pretend to subscribe.
     *
     * No payment is taken and nothing on the emergency path reads the result. It exists so the
     * plan screens in the app have real state to render, and every response it produces is
     * flagged `simulated: true`.
     */
    @Transactional
    fun simulateSubscription(userId: String, planType: PlanType): SubscriptionResponse {
        val family = familyRepository.findByOwnerUserId(userId)

        if (planType == PlanType.FAMILY && family == null) {
            throw ConflictException("Create a family group before choosing the family plan")
        }

        val existing = subscriptionRepository.findByOwnerUserId(userId)
        val subscription = existing?.apply {
            this.planType = planType
            this.status = SubscriptionStatus.ACTIVE
            this.familyId = family?.id
            this.startDate = LocalDate.now()
            this.endDate = LocalDate.now().plusMonths(1)
        } ?: Subscription(
            ownerUserId = userId,
            familyId = family?.id,
            planType = planType,
            status = SubscriptionStatus.ACTIVE,
            startDate = LocalDate.now(),
            endDate = LocalDate.now().plusMonths(1),
            simulated = true,
        )

        return SubscriptionResponse.from(subscriptionRepository.save(subscription))
    }

    private fun requireOwnedFamily(familyId: String, ownerId: String): Family {
        val family = familyRepository.findById(familyId).orElseThrow {
            NotFoundException("No such family group")
        }
        if (family.ownerUserId != ownerId) {
            throw ForbiddenException("Only the group owner can change its members")
        }
        return family
    }

    private fun describe(family: Family): FamilyResponse {
        val members = memberRepository.findAllByFamilyId(family.id)
        val accounts = userRepository
            .findAllByIdIn(members.map { it.userId })
            .associateBy { it.id }

        return FamilyResponse(
            id = family.id,
            name = family.name,
            ownerUserId = family.ownerUserId,
            members = members.mapNotNull { member ->
                val account = accounts[member.userId] ?: return@mapNotNull null
                FamilyMemberResponse(
                    userId = account.id,
                    fullName = account.fullName,
                    phone = account.phone,
                    relationship = member.relationship,
                    isOwner = account.id == family.ownerUserId,
                )
            },
            createdAt = family.createdAt,
        )
    }

    companion object {
        const val MAX_MEMBERS = 6
    }
}
