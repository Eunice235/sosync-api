package com.sosync.web

import com.sosync.common.CurrentUser
import com.sosync.service.notification.NotificationService
import com.sosync.web.dto.NotificationResponse
import com.sosync.web.dto.PagedResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/notifications")
@Tag(
    name = "6. Notifications",
    description = "The in-app tray, and the delivery log that shows what actually went out.",
)
class NotificationController(private val notificationService: NotificationService) {

    @GetMapping
    @Operation(
        summary = "My notifications",
        description = """
            Every alert addressed to me, on every channel. The `simulated` flag is true where
            no real gateway was connected and nothing left the building: surfaced rather than
            hidden, because a safety tool that overstates delivery is worse than one that
            admits it.
        """,
    )
    fun tray(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "30") size: Int,
    ): PagedResponse<NotificationResponse> =
        PagedResponse.of(
            notificationService.tray(CurrentUser.id(), page, size.coerceAtMost(100)),
        ) { it }

    @GetMapping("/unread-count")
    @Operation(summary = "How many alerts I have not acknowledged")
    fun unreadCount(): Map<String, Long> =
        mapOf("count" to notificationService.unacknowledgedCount(CurrentUser.id()))

    @PostMapping("/{notificationId}/acknowledge")
    @Operation(
        summary = "Confirm I have seen this alert",
        description = "Written to the incident timeline too. It changes something the reporter " +
            "needs to know: not that an alert was sent, but that somebody who can help has " +
            "seen it.",
    )
    fun acknowledge(@PathVariable notificationId: String): NotificationResponse =
        notificationService.acknowledge(notificationId, CurrentUser.id())
}
