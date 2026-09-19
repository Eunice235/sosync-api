package com.sosync.web

import com.sosync.common.CurrentUser
import com.sosync.domain.IncidentStatus
import com.sosync.domain.Language
import com.sosync.domain.ResponderVerificationStatus
import com.sosync.domain.Role
import com.sosync.service.admin.AdminService
import com.sosync.service.admin.AuditService
import com.sosync.service.incident.IncidentQueryService
import com.sosync.service.responder.ResponderService
import com.sosync.web.dto.AdminStatsResponse
import com.sosync.web.dto.IncidentResponse
import com.sosync.web.dto.PagedResponse
import com.sosync.web.dto.ResponderAdminResponse
import com.sosync.web.dto.ResponderVerificationRequest
import com.sosync.web.dto.UserResponse
import com.sosync.web.dto.UserStatusRequest
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * Operator surface. Locked to ADMIN by the filter chain.
 *
 * Note the absence of anything that touches an emergency directly: no endpoint cancels somebody
 * else's incident, and reading one goes through the same audited path everyone else uses.
 * Administration owns the trust layer, not the emergencies.
 */
@RestController
@RequestMapping("/api/admin")
@Tag(
    name = "9. Administration",
    description = "Responder verification, account status and platform statistics.",
)
class AdminController(
    private val adminService: AdminService,
    private val responderService: ResponderService,
    private val queryService: IncidentQueryService,
    private val auditService: AuditService,
) {

    @GetMapping("/stats")
    @Operation(
        summary = "Platform statistics",
        description = "`medianSecondsToAccept` is the number that says whether any of this " +
            "works: how long it takes, typically, for a responder to take an alert. Median " +
            "rather than mean, so one incident nobody picked up cannot hide a hundred good ones.",
    )
    fun stats(): AdminStatsResponse = adminService.stats()

    @GetMapping("/users")
    @Operation(summary = "Accounts")
    fun users(
        @RequestParam(required = false) role: Role?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PagedResponse<UserResponse> =
        PagedResponse.of(adminService.users(role, page, size.coerceAtMost(100))) { it }

    @PutMapping("/users/{userId}/status")
    @Operation(
        summary = "Suspend or restore an account",
        description = "Refuses with 409 if that account has an emergency in progress. Cutting " +
            "somebody off mid-incident is the one moment an administrative action could do " +
            "real harm, so it is blocked rather than warned about.",
    )
    fun setUserStatus(
        @PathVariable userId: String,
        @Valid @RequestBody request: UserStatusRequest,
    ): UserResponse =
        adminService.setUserStatus(userId, request.status, CurrentUser.id())

    @GetMapping("/responders")
    @Operation(summary = "Responders, with verification state")
    fun responders(
        @RequestParam(required = false) status: ResponderVerificationStatus?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PagedResponse<ResponderAdminResponse> =
        PagedResponse.of(
            responderService.listForAdmin(status, page, size.coerceAtMost(100)),
        ) { it }

    @PutMapping("/responders/{responderId}/verification")
    @Operation(
        summary = "Verify, reject or disable a responder",
        description = """
            The trust boundary of the platform. Everything the responder role can do, up to
            reading a stranger's live location during an emergency, follows from an
            administrator making this call, so it is audited with the actor recorded.

            Anything other than VERIFIED also takes the responder off shift, so a disabled
            account cannot sit in the available pool.
        """,
    )
    fun setVerification(
        @PathVariable responderId: String,
        @Valid @RequestBody request: ResponderVerificationRequest,
    ): ResponderAdminResponse =
        responderService.setVerification(responderId, request.status, CurrentUser.id())

    @GetMapping("/incidents")
    @Operation(summary = "All incidents, newest first")
    fun incidents(
        @RequestParam(required = false) status: IncidentStatus?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PagedResponse<IncidentResponse> =
        PagedResponse.of(
            queryService.allIncidents(status, page, size.coerceAtMost(100)),
        ) { it }

    @GetMapping("/incidents/{incidentId}/audit")
    @Operation(
        summary = "Audit trail for an incident",
        description = "Who did what, and who looked at the location. Location access is the " +
            "sensitive capability here, so reads are recorded alongside writes.",
    )
    fun audit(@PathVariable incidentId: String): List<Map<String, Any?>> =
        auditService.forTarget("incident", incidentId).map {
            mapOf<String, Any?>(
                "actorId" to it.actorId,
                "action" to it.action,
                "detail" to it.detail,
                "occurredAt" to it.occurredAt,
            )
        }
}

/**
 * Unauthenticated metadata, so a client can populate pickers and status labels without
 * hardcoding enum values that might drift.
 */
@RestController
@RequestMapping("/api/meta")
@Tag(name = "10. Metadata", description = "Enum values and service information.")
class MetaController {

    @GetMapping("/enums")
    @Operation(summary = "Every enum the API accepts or returns")
    fun enums(): Map<String, List<String>> = mapOf(
        "incidentStatus" to IncidentStatus.entries.map { it.name },
        "openIncidentStatus" to IncidentStatus.OPEN.map { it.name },
        "role" to Role.entries.map { it.name },
        "language" to Language.entries.map { it.name },
        "responderVerificationStatus" to ResponderVerificationStatus.entries.map { it.name },
    )

    @GetMapping("/languages")
    @Operation(
        summary = "Languages alerts can be delivered in",
        description = "Only languages with a complete, reviewed message catalogue appear here. " +
            "Set one per account with PUT /api/auth/me, or per trusted contact when adding them.",
    )
    fun languages(): List<Map<String, String>> = Language.entries.map {
        mapOf("code" to it.code, "name" to it.englishName, "nativeName" to it.nativeName)
    }

    @GetMapping("/info")
    @Operation(summary = "What this build is and what it is not")
    fun info(): Map<String, Any> = mapOf(
        "service" to "SOSync API",
        "version" to "0.1.0",
        "stage" to "hackathon proof of concept",
        "time" to Instant.now(),
        "limits" to listOf(
            "Not connected to any national emergency service.",
            "No real SMS or push gateway: deliveries are recorded as SIMULATED.",
            "Subscription state is simulated and never gates an emergency.",
            "Responders are private, campus or community responders verified by an administrator.",
        ),
    )
}
