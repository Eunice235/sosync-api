package com.sosync.web.dto

import com.sosync.domain.AccountStatus
import com.sosync.domain.IncidentStatus
import com.sosync.domain.Language
import com.sosync.domain.PlanType
import com.sosync.domain.ResponderAvailability
import com.sosync.domain.ResponderVerificationStatus
import com.sosync.domain.Role
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant

// ══════════════════════════════════════════════════════════════════════════════════
// Authentication
// ══════════════════════════════════════════════════════════════════════════════════

@Schema(description = "Create an account. Phone number is the identifier and the SMS address.")
data class RegisterRequest(
    @field:NotBlank(message = "Your name is required")
    @field:Size(max = 120)
    val fullName: String,

    @Schema(
        description = "Any readable format. Stored in E.164, so 0712345678 and +254712345678 " +
            "are the same number.",
        example = "+254712345678",
    )
    @field:NotBlank(message = "A phone number is required")
    val phone: String,

    @field:Email(message = "That does not look like an email address")
    @field:Size(max = 160)
    val email: String? = null,

    @field:NotBlank(message = "A password is required")
    @field:Size(min = 8, max = 72, message = "Use at least 8 characters")
    val password: String,

    @Schema(
        description = "Defaults to USER. A RESPONDER registration is created PENDING and " +
            "receives no alerts until an administrator verifies it. ADMIN cannot be " +
            "self-assigned.",
    )
    val role: Role? = null,

    @Schema(description = "Required when role is RESPONDER: the guard or security company.")
    @field:Size(max = 160)
    val organization: String? = null,

    @Schema(
        description = "Language for alerts sent to this account. Defaults to EN. See " +
            "GET /api/meta/languages.",
    )
    val preferredLanguage: Language? = null,
)

@Schema(description = "Sign in with phone number or email.")
data class LoginRequest(
    @Schema(description = "Phone number or email address", example = "+254712000001")
    @field:NotBlank(message = "Enter your phone number or email")
    val identifier: String,

    @field:NotBlank(message = "Enter your password")
    val password: String,
)

data class RefreshRequest(
    @field:NotBlank
    val refreshToken: String,
)

data class VerifyPhoneRequest(
    @field:NotBlank val phone: String,
    @field:NotBlank @field:Size(min = 6, max = 6) val code: String,
)

data class ForgotPasswordRequest(
    @field:NotBlank val phone: String,
)

data class ResetPasswordRequest(
    @field:NotBlank val phone: String,
    @field:NotBlank @field:Size(min = 6, max = 6) val code: String,
    @field:NotBlank @field:Size(min = 8, max = 72) val newPassword: String,
)

@Schema(
    description = "Set or change the PIN that is required to cancel an active emergency. " +
        "The account password is required to change it, so a stolen unlocked phone cannot " +
        "quietly replace the PIN and then cancel.",
)
data class SetSafetyPinRequest(
    @Schema(example = "4821")
    @field:NotBlank(message = "A PIN is required")
    @field:Pattern(regexp = "\\d{4,8}", message = "Use 4 to 8 digits")
    val pin: String,

    @field:NotBlank(message = "Confirm with your account password")
    val password: String,
)

// ══════════════════════════════════════════════════════════════════════════════════
// Profile and contacts
// ══════════════════════════════════════════════════════════════════════════════════

data class UpdateProfileRequest(
    @field:Size(max = 120) val fullName: String? = null,
    @field:Email @field:Size(max = 160) val email: String? = null,

    @Schema(
        description = "Shown to a responder on arrival: medical conditions, what you look " +
            "like, who to expect with you.",
    )
    @field:Size(max = 500)
    val emergencyNote: String? = null,

    @field:Size(max = 500) val photoUrl: String? = null,

    @Schema(description = "Change the language alerts are delivered in.")
    val preferredLanguage: Language? = null,
)

@Schema(description = "A trusted contact. Does not need an SOSync account: they get the SMS.")
data class ContactRequest(
    @field:NotBlank(message = "A name is required")
    @field:Size(max = 120)
    val name: String,

    @field:NotBlank(message = "A phone number is required")
    val phone: String,

    @Schema(example = "Sister")
    @field:Size(max = 60)
    val relationship: String? = null,

    @Schema(description = "1 is highest. Priority 1 contacts are guaranteed an SMS.")
    @field:Min(1) @field:Max(9)
    val priority: Int = 1,

    val notificationEnabled: Boolean = true,

    @Schema(
        description = "Language for this contact. Leave null to alert them in your own " +
            "language. Worth setting for a contact with no SOSync account, since there is no " +
            "profile to read a preference from; where they do have an account, theirs wins.",
    )
    val language: Language? = null,
)

// ══════════════════════════════════════════════════════════════════════════════════
// Emergencies
// ══════════════════════════════════════════════════════════════════════════════════

@Schema(
    description = "Raise an emergency. Sent once the press-and-hold completes on the device. " +
        "Coordinates are optional: a missing GPS fix must never stop the alert going out.",
)
data class TriggerSosRequest(
    @field:DecimalMin("-90.0") @field:DecimalMax("90.0")
    val latitude: Double? = null,

    @field:DecimalMin("-180.0") @field:DecimalMax("180.0")
    val longitude: Double? = null,

    @Schema(description = "Accuracy of the fix in metres, as reported by the device.")
    val accuracy: Double? = null,

    @Schema(
        description = "When true the reporting device shows and sounds nothing. Defaults to " +
            "true: the person causing the danger must not see the alarm being raised.",
    )
    val silent: Boolean = true,

    @Schema(description = "Optional context typed before the alert, if there was time.")
    @field:Size(max = 500)
    val note: String? = null,
)

@Schema(description = "A position recorded during an active incident.")
data class LocationRequest(
    @field:DecimalMin("-90.0") @field:DecimalMax("90.0")
    val latitude: Double,

    @field:DecimalMin("-180.0") @field:DecimalMax("180.0")
    val longitude: Double,

    val accuracy: Double? = null,

    @Schema(
        description = "Device timestamp of the fix. Send this for fixes taken while offline " +
            "so the trail keeps its real times once the phone reconnects. Defaults to now.",
    )
    val recordedAt: Instant? = null,
)

@Schema(
    description = "Stand down an emergency. Only the reporter can do this, and only with the " +
        "safety PIN. A wrong PIN leaves the incident running.",
)
data class CancelIncidentRequest(
    @field:NotBlank(message = "Your safety PIN is required")
    val pin: String,

    @field:Size(max = 200)
    val reason: String? = null,
)

// ══════════════════════════════════════════════════════════════════════════════════
// Responder
// ══════════════════════════════════════════════════════════════════════════════════

@Schema(description = "Move an accepted incident forward. Forward only.")
data class ResponderStatusRequest(
    @Schema(allowableValues = ["RESPONDING", "ARRIVED", "RESOLVED"])
    val status: IncidentStatus,

    @field:Size(max = 500)
    val note: String? = null,
)

data class ResponderAvailabilityRequest(
    val availability: ResponderAvailability,
)

@Schema(
    description = "A responder position. Used to match nearby alerts, and ignored once it goes " +
        "stale, so this needs sending while on shift.",
)
data class ResponderLocationRequest(
    @field:DecimalMin("-90.0") @field:DecimalMax("90.0")
    val latitude: Double,

    @field:DecimalMin("-180.0") @field:DecimalMax("180.0")
    val longitude: Double,
)

// ══════════════════════════════════════════════════════════════════════════════════
// Family and plans
// ══════════════════════════════════════════════════════════════════════════════════

data class CreateFamilyRequest(
    @field:NotBlank @field:Size(max = 120) val name: String,
)

data class AddFamilyMemberRequest(
    @Schema(description = "Phone number of an existing SOSync account.")
    @field:NotBlank
    val phone: String,

    @field:Size(max = 60) val relationship: String? = null,
)

@Schema(
    description = "Simulate a subscription. No payment is taken and nothing on the emergency " +
        "path consults subscription state.",
)
data class SimulateSubscriptionRequest(
    val planType: PlanType,
)

// ══════════════════════════════════════════════════════════════════════════════════
// Administration
// ══════════════════════════════════════════════════════════════════════════════════

@Schema(description = "Verify or disable a responder. The trust boundary of the platform.")
data class ResponderVerificationRequest(
    val status: ResponderVerificationStatus,
)

data class UserStatusRequest(
    val status: AccountStatus,
)
