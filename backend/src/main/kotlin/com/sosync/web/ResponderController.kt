package com.sosync.web

import com.sosync.common.BadRequestException
import com.sosync.common.CurrentUser
import com.sosync.domain.IncidentStatus
import com.sosync.service.incident.IncidentQueryService
import com.sosync.service.incident.IncidentService
import com.sosync.service.incident.ViewerRelationship
import com.sosync.service.responder.ResponderService
import com.sosync.web.dto.IncidentResponse
import com.sosync.web.dto.PagedResponse
import com.sosync.web.dto.ResponderAlertResponse
import com.sosync.web.dto.ResponderAvailabilityRequest
import com.sosync.web.dto.ResponderLocationRequest
import com.sosync.web.dto.ResponderStatusRequest
import com.sosync.web.dto.ResponderSummary
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * The responder surface. Locked to RESPONDER and ADMIN by the filter chain, and every action
 * re-checks that the responder is verified and holds the incident.
 */
@RestController
@RequestMapping("/api/responder")
@Tag(
    name = "5. Responder",
    description = "Guard-side: see alerts, accept one, travel to it, close it out.",
)
class ResponderController(
    private val responderService: ResponderService,
    private val incidentService: IncidentService,
    private val queryService: IncidentQueryService,
) {

    @GetMapping("/me")
    @Operation(summary = "My responder profile and verification state")
    fun me(): ResponderSummary = responderService.profileFor(CurrentUser.id())

    @PutMapping("/availability")
    @Operation(
        summary = "Go on or off shift",
        description = "AVAILABLE starts receiving alerts. Verification is unaffected: an " +
            "unverified responder can be available all day and still be sent nothing.",
    )
    fun setAvailability(
        @Valid @RequestBody request: ResponderAvailabilityRequest,
    ): ResponderSummary =
        responderService.setAvailability(CurrentUser.id(), request.availability)

    @PutMapping("/location")
    @Operation(
        summary = "Report my position",
        description = "Needed while on shift so alerts can be matched by distance. A position " +
            "older than the freshness window stops being used, because a fix from this " +
            "morning says nothing about where you are now.",
    )
    fun updateLocation(
        @Valid @RequestBody request: ResponderLocationRequest,
    ): ResponderSummary =
        responderService.updateLocation(
            CurrentUser.id(),
            request.latitude,
            request.longitude,
        )

    @GetMapping("/alerts")
    @Operation(
        summary = "My alert list",
        description = """
            Unclaimed emergencies within the dispatch radius, plus any incident I already hold.

            Sorted by how long each alert has been waiting, not by distance. The nearest alert
            is not the most urgent one; the one nobody has taken for four minutes is.

            An unverified or off-shift responder sees only what they already hold.
        """,
    )
    fun alerts(): List<ResponderAlertResponse> =
        queryService.alertsForResponder(CurrentUser.id())

    @GetMapping("/incidents/{incidentId}")
    @Operation(
        summary = "Alert detail",
        description = "Carries what is needed to respond and no more: who the person is, the " +
            "number to reach them, any note that helps identify them, and the location.",
    )
    fun detail(@PathVariable incidentId: String): IncidentResponse =
        queryService.detail(incidentId, CurrentUser.id(), includeTrail = true)

    @PostMapping("/incidents/{incidentId}/accept")
    @Operation(
        summary = "Take this emergency",
        description = "First to accept wins. A second responder tapping accept gets 409 and " +
            "is told somebody already has it, rather than silently overwriting them.",
    )
    fun accept(@PathVariable incidentId: String): IncidentResponse {
        val userId = CurrentUser.id()
        val incident = incidentService.accept(incidentId, userId)
        return queryService.summarise(incident, userId, ViewerRelationship.ASSIGNED_RESPONDER)
    }

    @PostMapping("/incidents/{incidentId}/status")
    @Operation(
        summary = "Move it forward: RESPONDING, ARRIVED, RESOLVED",
        description = "Forward only, and only by the responder holding it. A step backwards " +
            "would make the timeline unreadable, which defeats the point of having one.",
    )
    fun advance(
        @PathVariable incidentId: String,
        @Valid @RequestBody request: ResponderStatusRequest,
    ): IncidentResponse {
        if (request.status !in ALLOWED_TARGETS) {
            throw BadRequestException(
                "status must be one of ${ALLOWED_TARGETS.joinToString(", ")}",
            )
        }

        val userId = CurrentUser.id()
        val incident = incidentService.advance(
            incidentId = incidentId,
            responderUserId = userId,
            target = request.status,
            note = request.note,
        )
        return queryService.summarise(incident, userId, ViewerRelationship.ASSIGNED_RESPONDER)
    }

    @GetMapping("/incidents")
    @Operation(summary = "Emergencies I have handled")
    fun history(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PagedResponse<IncidentResponse> =
        PagedResponse.of(
            queryService.historyForResponder(CurrentUser.id(), page, size.coerceAtMost(100)),
        ) { it }

    companion object {
        /** Cancelling is the reporter's alone, so it is not a target a responder can set. */
        private val ALLOWED_TARGETS = listOf(
            IncidentStatus.RESPONDING,
            IncidentStatus.ARRIVED,
            IncidentStatus.RESOLVED,
        )
    }
}
