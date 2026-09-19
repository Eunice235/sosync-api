package com.sosync.web

import com.sosync.common.CurrentUser
import com.sosync.service.auth.AuthService
import com.sosync.web.dto.AuthResponse
import com.sosync.web.dto.ForgotPasswordRequest
import com.sosync.web.dto.LoginRequest
import com.sosync.web.dto.MessageResponse
import com.sosync.web.dto.RefreshRequest
import com.sosync.web.dto.RegisterRequest
import com.sosync.web.dto.ResetPasswordRequest
import com.sosync.web.dto.SetSafetyPinRequest
import com.sosync.web.dto.UpdateProfileRequest
import com.sosync.web.dto.UserResponse
import com.sosync.web.dto.VerifyPhoneRequest
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
@Tag(name = "1. Authentication", description = "Accounts, sign-in and the safety PIN")
class AuthController(private val authService: AuthService) {

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "")
    @Operation(
        summary = "Create an account",
        description = "A RESPONDER registration is created PENDING and receives no alerts " +
            "until an administrator verifies it. ADMIN cannot be self-assigned.",
    )
    fun register(@Valid @RequestBody request: RegisterRequest): AuthResponse {
        val result = authService.register(
            fullName = request.fullName,
            rawPhone = request.phone,
            email = request.email,
            password = request.password,
            requestedRole = request.role,
            organization = request.organization,
            preferredLanguage = request.preferredLanguage,
        )
        return AuthResponse.from(result.user, result.tokens, result.simulatedVerificationCode)
    }

    @PostMapping("/login")
    @SecurityRequirement(name = "")
    @Operation(
        summary = "Sign in with phone number or email",
        description = "Demo accounts all use the password Sosync#2026.",
    )
    fun login(@Valid @RequestBody request: LoginRequest): AuthResponse {
        val result = authService.login(request.identifier, request.password)
        return AuthResponse.from(result.user, result.tokens)
    }

    @PostMapping("/refresh")
    @SecurityRequirement(name = "")
    @Operation(summary = "Exchange a refresh token for a new pair")
    fun refresh(@Valid @RequestBody request: RefreshRequest): AuthResponse {
        val result = authService.refresh(request.refreshToken)
        return AuthResponse.from(result.user, result.tokens)
    }

    @PostMapping("/verify-phone")
    @SecurityRequirement(name = "")
    @Operation(
        summary = "Confirm a phone number with the code",
        description = "With no SMS gateway connected, the code is returned by /register.",
    )
    fun verifyPhone(@Valid @RequestBody request: VerifyPhoneRequest): UserResponse =
        UserResponse.from(authService.verifyPhone(request.phone, request.code))

    @PostMapping("/forgot-password")
    @SecurityRequirement(name = "")
    @Operation(
        summary = "Request a password reset code",
        description = "Answers the same way whether or not the number is registered, so it " +
            "cannot be used to find out who has an account.",
    )
    fun forgotPassword(@Valid @RequestBody request: ForgotPasswordRequest): MessageResponse {
        val code = authService.forgotPassword(request.phone)
        return MessageResponse(
            if (code != null) {
                "If that number has an account, a reset code has been sent. " +
                    "Simulated code: $code"
            } else {
                "If that number has an account, a reset code has been sent."
            },
        )
    }

    @PostMapping("/reset-password")
    @SecurityRequirement(name = "")
    @Operation(summary = "Set a new password using a reset code")
    fun resetPassword(@Valid @RequestBody request: ResetPasswordRequest): MessageResponse {
        authService.resetPassword(request.phone, request.code, request.newPassword)
        return MessageResponse("Password updated. Sign in with your new password.")
    }

    @GetMapping("/me")
    @Operation(summary = "The signed-in account")
    fun me(): UserResponse = UserResponse.from(authService.requireUser(CurrentUser.id()))

    @PutMapping("/me")
    @Operation(summary = "Update the signed-in profile")
    fun updateProfile(@Valid @RequestBody request: UpdateProfileRequest): UserResponse =
        UserResponse.from(
            authService.updateProfile(
                userId = CurrentUser.id(),
                fullName = request.fullName,
                email = request.email,
                emergencyNote = request.emergencyNote,
                photoUrl = request.photoUrl,
                preferredLanguage = request.preferredLanguage,
            ),
        )

    @PutMapping("/me/safety-pin")
    @Operation(
        summary = "Set or change the cancellation PIN",
        description = "Requires the account password, so an unlocked phone in the wrong hands " +
            "cannot replace the PIN and then cancel an active emergency.",
    )
    fun setSafetyPin(@Valid @RequestBody request: SetSafetyPinRequest): UserResponse =
        UserResponse.from(
            authService.setSafetyPin(CurrentUser.id(), request.pin, request.password),
        )

    @PostMapping("/logout")
    @Operation(
        summary = "Sign out",
        description = "Clients discard their tokens. Tokens are stateless in the prototype, " +
            "so this cannot revoke a refresh token that has already been issued: see the " +
            "known limits in the README.",
    )
    fun logout(): MessageResponse = MessageResponse("Signed out. Discard your tokens.")
}
