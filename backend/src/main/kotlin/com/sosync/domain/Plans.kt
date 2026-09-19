package com.sosync.domain

import com.sosync.common.NanoIds
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate

/**
 * A household. Members receive each other's SOS alerts without having to nominate each other
 * as individual trusted contacts.
 */
@Entity
@Table(name = "families")
class Family(
    @Id
    @Column(length = 21)
    var id: String = NanoIds.generate(),

    @Column(name = "owner_user_id", nullable = false, length = 21)
    var ownerUserId: String,

    @Column(nullable = false, length = 120)
    var name: String,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "family_members")
class FamilyMember(
    @Id
    @Column(length = 21)
    var id: String = NanoIds.generate(),

    @Column(name = "family_id", nullable = false, length = 21)
    var familyId: String,

    @Column(name = "user_id", nullable = false, length = 21)
    var userId: String,

    @Column(length = 60)
    var relationship: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)

/**
 * Entitlement state.
 *
 * [simulated] is true for everything the prototype creates, and no code on the emergency path
 * reads this table. That is deliberate: gating a panic button on a payment check is the kind of
 * decision that has to be made openly, and it is not one a hackathon demo should smuggle in.
 */
@Entity
@Table(name = "subscriptions")
class Subscription(
    @Id
    @Column(length = 21)
    var id: String = NanoIds.generate(),

    @Column(name = "owner_user_id", nullable = false, length = 21)
    var ownerUserId: String,

    @Column(name = "family_id", length = 21)
    var familyId: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_type", nullable = false, length = 32)
    var planType: PlanType,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var status: SubscriptionStatus,

    @Column(name = "start_date", nullable = false)
    var startDate: LocalDate = LocalDate.now(),

    @Column(name = "end_date")
    var endDate: LocalDate? = null,

    @Column(nullable = false)
    var simulated: Boolean = true,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)
