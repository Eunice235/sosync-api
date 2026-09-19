package com.sosync.web

import com.sosync.common.CurrentUser
import com.sosync.service.family.FamilyService
import com.sosync.web.dto.AddFamilyMemberRequest
import com.sosync.web.dto.CreateFamilyRequest
import com.sosync.web.dto.FamilyResponse
import com.sosync.web.dto.PlanResponse
import com.sosync.web.dto.SimulateSubscriptionRequest
import com.sosync.web.dto.SubscriptionResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/families")
@Tag(
    name = "7. Family groups",
    description = "Households. Members are alerted for each other without nominating " +
        "everyone individually.",
)
class FamilyController(private val familyService: FamilyService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a family group")
    fun create(@Valid @RequestBody request: CreateFamilyRequest): FamilyResponse =
        familyService.create(CurrentUser.id(), request.name)

    @GetMapping("/mine")
    @Operation(summary = "Groups I own or belong to")
    fun mine(): List<FamilyResponse> = familyService.forUser(CurrentUser.id())

    @PostMapping("/{familyId}/members")
    @Operation(
        summary = "Add a member by phone number",
        description = "Requires an existing SOSync account. Family membership is mutual " +
            "visibility of emergencies, which is not something to confer on a number whose " +
            "owner has not signed up. Use a trusted contact for anyone else.",
    )
    fun addMember(
        @PathVariable familyId: String,
        @Valid @RequestBody request: AddFamilyMemberRequest,
    ): FamilyResponse =
        familyService.addMember(
            ownerId = CurrentUser.id(),
            familyId = familyId,
            rawPhone = request.phone,
            relationship = request.relationship,
        )

    @DeleteMapping("/{familyId}/members/{memberUserId}")
    @Operation(summary = "Remove a member")
    fun removeMember(
        @PathVariable familyId: String,
        @PathVariable memberUserId: String,
    ): FamilyResponse =
        familyService.removeMember(CurrentUser.id(), familyId, memberUserId)
}

@RestController
@Tag(
    name = "8. Plans",
    description = "Simulated entitlement. No payment is taken, and nothing on the emergency " +
        "path consults it.",
)
class PlanController(private val familyService: FamilyService) {

    @GetMapping("/api/plans")
    @SecurityRequirement(name = "")
    @Operation(
        summary = "The plan catalogue",
        description = "Note what is not a paid feature: the SOS button, location sharing and " +
            "responder dispatch are available on every tier including none. Putting an " +
            "emergency behind a paywall is a decision to make openly, not one a prototype " +
            "should imply.",
    )
    fun plans(): List<PlanResponse> = familyService.plans()

    @GetMapping("/api/subscriptions/mine")
    @Operation(summary = "My simulated subscription")
    fun mine(): ResponseEntity<SubscriptionResponse> =
        familyService.subscriptionFor(CurrentUser.id())
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.noContent().build()

    @PostMapping("/api/subscriptions/simulate")
    @Operation(
        summary = "Simulate subscribing to a plan",
        description = "Sets entitlement state so the plan screens have something real to " +
            "render. Every response is flagged `simulated: true`.",
    )
    fun simulate(
        @Valid @RequestBody request: SimulateSubscriptionRequest,
    ): SubscriptionResponse =
        familyService.simulateSubscription(CurrentUser.id(), request.planType)
}
