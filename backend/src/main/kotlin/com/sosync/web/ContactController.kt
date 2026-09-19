package com.sosync.web

import com.sosync.common.CurrentUser
import com.sosync.service.contact.ContactService
import com.sosync.service.incident.IncidentQueryService
import com.sosync.web.dto.ContactRequest
import com.sosync.web.dto.ContactResponse
import com.sosync.web.dto.IncidentResponse
import com.sosync.web.dto.MessageResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/me/contacts")
@Tag(
    name = "2. Trusted contacts",
    description = "Who gets told. A contact does not need an SOSync account: they get the SMS.",
)
class ContactController(private val contactService: ContactService) {

    @GetMapping
    @Operation(
        summary = "My trusted contacts",
        description = "`hasAccount` tells you which of them also receive in-app alerts and " +
            "can follow an incident, and which are SMS-only.",
    )
    fun list(): List<ContactResponse> = contactService.list(CurrentUser.id())

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "Add a trusted contact",
        description = "Any phone format is accepted and stored in E.164, so 0712345678 and " +
            "+254712345678 are recognised as the same number. At most " +
            "${ContactService.MAX_CONTACTS} contacts, so an SOS cannot become a bulk SMS blast.",
    )
    fun add(@Valid @RequestBody request: ContactRequest): ContactResponse =
        contactService.add(
            userId = CurrentUser.id(),
            name = request.name,
            rawPhone = request.phone,
            relationship = request.relationship,
            priority = request.priority,
            notificationEnabled = request.notificationEnabled,
            language = request.language,
        )

    @PutMapping("/{contactId}")
    @Operation(summary = "Update a trusted contact")
    fun update(
        @PathVariable contactId: String,
        @Valid @RequestBody request: ContactRequest,
    ): ContactResponse =
        contactService.update(
            userId = CurrentUser.id(),
            contactId = contactId,
            name = request.name,
            rawPhone = request.phone,
            relationship = request.relationship,
            priority = request.priority,
            notificationEnabled = request.notificationEnabled,
            language = request.language,
        )

    @DeleteMapping("/{contactId}")
    @Operation(summary = "Remove a trusted contact")
    fun remove(@PathVariable contactId: String): MessageResponse {
        contactService.remove(CurrentUser.id(), contactId)
        return MessageResponse("Contact removed")
    }
}

@RestController
@RequestMapping("/api/alerts")
@Tag(
    name = "4. Trusted contact inbox",
    description = "The other side of an alert: emergencies raised by people who nominated you.",
)
class AlertInboxController(private val queryService: IncidentQueryService) {

    @GetMapping
    @Operation(
        summary = "Emergencies I am watching over",
        description = """
            Every open emergency raised by someone who has you as a trusted contact, or who is
            in a family group with you. Matched on your phone number, so being nominated works
            whether or not you had an account at the time.

            This is what the app polls to show a contact that somebody needs help.
        """,
    )
    fun inbox(
        @RequestParam(defaultValue = "false") includeClosed: Boolean,
    ): List<IncidentResponse> =
        queryService.alertsForContact(CurrentUser.id(), includeClosed)
}
