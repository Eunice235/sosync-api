package com.sosync.common

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Instant

private val log = KotlinLogging.logger {}

/**
 * Base for the failures the API reports deliberately. Anything not derived from this is a bug
 * and becomes a 500 with no detail leaked to the caller.
 */
sealed class ApiException(
    val status: HttpStatus,
    val code: String,
    override val message: String,
) : RuntimeException(message)

class NotFoundException(message: String) :
    ApiException(HttpStatus.NOT_FOUND, "not_found", message)

class BadRequestException(message: String) :
    ApiException(HttpStatus.BAD_REQUEST, "bad_request", message)

class ConflictException(message: String) :
    ApiException(HttpStatus.CONFLICT, "conflict", message)

class UnauthorizedException(message: String = "Authentication required") :
    ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", message)

class ForbiddenException(message: String = "You do not have permission to do that") :
    ApiException(HttpStatus.FORBIDDEN, "forbidden", message)

/**
 * Wrong safety PIN on a cancellation attempt.
 *
 * Separated from a plain 403 because the client has to tell these apart: a failed PIN must keep
 * the incident running and stay silent on the reporting device, since the person entering it
 * may not be the person who raised the alarm.
 */
class InvalidSafetyPinException(message: String = "Incorrect safety PIN") :
    ApiException(HttpStatus.FORBIDDEN, "invalid_safety_pin", message)

data class ErrorResponse(
    val error: String,
    val message: String,
    val fields: Map<String, String>? = null,
    val timestamp: Instant = Instant.now(),
)

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(ApiException::class)
    fun handleApi(ex: ApiException): ResponseEntity<ErrorResponse> {
        log.debug { "${ex.code}: ${ex.message}" }
        return ResponseEntity.status(ex.status).body(ErrorResponse(ex.code, ex.message))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val fields = ex.bindingResult.fieldErrors.associate {
            it.field to (it.defaultMessage ?: "is invalid")
        }
        return ResponseEntity.badRequest().body(
            ErrorResponse("validation_failed", "Some fields need attention", fields),
        )
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(ex: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest().body(
            ErrorResponse("malformed_request", "The request body could not be read"),
        )

    /**
     * Last resort. The cause is logged in full and the caller is told nothing about it: stack
     * details in an error body are how internals leak.
     */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ErrorResponse> {
        log.error(ex) { "Unhandled exception" }
        return ResponseEntity.internalServerError().body(
            ErrorResponse("internal_error", "Something went wrong on our side"),
        )
    }
}
