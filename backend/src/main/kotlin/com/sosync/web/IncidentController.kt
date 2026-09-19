package com.sosync.web

import com.sosync.common.CurrentUser
import com.sosync.service.incident.IncidentQueryService
import com.sosync.service.incident.IncidentService
import com.sosync.service.incident.ViewerRelationship
import com.sosync.service.notification.AlertStrings
import com.sosync.web.dto.CancelIncidentRequest
import com.sosync.web.dto.IncidentResponse
import com.sosync.web.dto.LocationRequest
import com.sosync.web.dto.LocationResponse
import com.sosync.web.dto.NotificationResponse
import com.sosync.web.dto.PagedResponse
import com.sosync.web.dto.TriggerSosRequest
import com.sosync.web.dto.TriggerSosResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/incidents")
@Tag(
    name = "3. Emergencies",
    description = "Raising, following and closing an emergency. The core of SOSync.",
)
class IncidentController(
    private val incidentService: IncidentService,
    private val queryService: IncidentQueryService,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "Raise an emergency (the SOS button)",
        description = """
            Sent once the press-and-hold completes on the device. The hold itself is a client
            concern; the server treats the arrival of this request as a deliberate act.

            Coordinates are optional. A missing GPS fix does not stop the alert: reaching people
            late with a location beats reaching them never because the phone was indoors.

            Pressing again while an emergency is already running returns that same incident with
            `alreadyActive: true` rather than starting a second one.
        """,
    )
    fun trigger(@Valid @RequestBody request: TriggerSosRequest): TriggerSosResponse {
        val userId = CurrentUser.id()
        val result = incidentService.trigger(
            userId = userId,
            latitude = request.latitude,
            longitude = request.longitude,
            accuracy = request.accuracy,
            silent = request.silent,
            note = request.note,
        )

        val incident = queryService.summarise(
            result.incident,
            userId,
            ViewerRelationship.REPORTER,
        )

        val recipientCount = result.notifications
            .mapNotNull { it.recipientLabel }
            .distinct()
            .size

        // The one string in an API response the server words itself, because it is shown to the
        // reporter verbatim at the moment they are least able to read carefully.
        val strings = AlertStrings.of(queryService.requireUser(userId).preferredLanguage)

        return TriggerSosResponse(
            incident = incident,
            alreadyActive = result.alreadyActive,
            notifications = result.notifications.map { NotificationResponse.from(it) },
            summary = when {
                result.alreadyActive -> strings.sosSummaryAlreadyActive()
                recipientCount == 0 -> strings.sosSummaryNobodyReached()
                else -> strings.sosSummarySent(recipientCount)
            },
        )
    }

    @GetMapping("/active")
    @Operation(
        summary = "My emergency in progress, if any",
        description = "What the app calls on launch to decide whether to show the live " +
            "emergency screen instead of the SOS button. Returns 204 when there is none.",
    )
    fun active(): ResponseEntity<IncidentResponse> =
        queryService.activeForUser(CurrentUser.id())
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.noContent().build()

    @GetMapping
    @Operation(summary = "My past emergencies")
    fun history(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PagedResponse<IncidentResponse> =
        PagedResponse.of(
            queryService.historyForUser(CurrentUser.id(), page, size.coerceAtMost(100)),
        ) { it }

    @GetMapping("/{incidentId}")
    @Operation(
        summary = "One emergency in full",
        description = """
            Readable by the reporter, their trusted contacts, their family members, the assigned
            responder, and any verified available responder while the alert is still unclaimed.

            An unrelated caller gets 404 rather than 403: confirming that an incident id exists
            would tell a stranger somebody raised an emergency.

            `location` and `locationTrail` are omitted entirely once a closed incident stops
            sharing with this viewer, rather than returned blank.
        """,
    )
    fun detail(
        @PathVariable incidentId: String,
        @RequestParam(defaultValue = "false") includeTrail: Boolean,
    ): IncidentResponse = queryService.detail(incidentId, CurrentUser.id(), includeTrail)

    @PostMapping("/{incidentId}/locations")
    @Operation(
        summary = "Report a position during an active emergency",
        description = """
            The device posts here periodically while an incident is open. Rejected with 409 once
            the incident closes, which is the mechanism behind the promise that sharing stops
            when the emergency ends: no code path stores a position outside an open incident.

            Send `recordedAt` for fixes captured while offline so the trail keeps its real times
            when the phone reconnects. Out-of-order uploads never overwrite a newer position.
        """,
    )
    fun recordLocation(
        @PathVariable incidentId: String,
        @Valid @RequestBody request: LocationRequest,
    ): IncidentResponse {
        val userId = CurrentUser.id()
        val incident = incidentService.recordLocation(
            incidentId = incidentId,
            userId = userId,
            latitude = request.latitude,
            longitude = request.longitude,
            accuracy = request.accuracy,
            recordedAt = request.recordedAt,
        )
        return queryService.summarise(incident, userId, ViewerRelationship.REPORTER)
    }

    @GetMapping("/{incidentId}/locations")
    @Operation(
        summary = "The location trail",
        description = "Every fix recorded during the incident, oldest first. Each point " +
            "carries its own age, so a watcher can tell a current position from a stale one. " +
            "Returns an empty list when the incident has closed and sharing has stopped.",
    )
    fun locationTrail(@PathVariable incidentId: String): List<LocationResponse> =
        queryService.locationTrail(incidentId, CurrentUser.id())

    @PostMapping("/{incidentId}/cancel")
    @Operation(
        summary = "Stand down an emergency (safety PIN required)",
        description = """
            Only the reporter can cancel, and only with their safety PIN. Not a contact, not a
            responder, not an administrator.

            A wrong PIN answers 403 `invalid_safety_pin` and **leaves the incident running**.
            The client should treat that as a silent failure and keep the screen outwardly
            calm: the person typing may not be the person who raised the alarm.

            Where no PIN has been set, the account password is accepted instead.
        """,
    )
    fun cancel(
        @PathVariable incidentId: String,
        @Valid @RequestBody request: CancelIncidentRequest,
    ): IncidentResponse {
        val userId = CurrentUser.id()
        val incident = incidentService.cancel(
            incidentId = incidentId,
            userId = userId,
            pin = request.pin,
            reason = request.reason,
        )
        return queryService.summarise(incident, userId, ViewerRelationship.REPORTER)
    }
}
