package com.sosync.common

import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt

/**
 * The caller comes from the token, never from the request body.
 *
 * This is the single rule the whole authorisation model rests on. No endpoint accepts an actor
 * id as input, so no caller can act as somebody else by editing a payload; services then
 * re-check that the caller owns the row they are touching.
 */
object CurrentUser {

    private fun jwtOrNull(): Jwt? =
        SecurityContextHolder.getContext()?.authentication?.principal as? Jwt

    /** The authenticated user id, or throws if the request is anonymous. */
    fun id(): String = idOrNull() ?: throw UnauthorizedException()

    fun idOrNull(): String? = jwtOrNull()?.subject

    /** Role as carried on the token. Authorisation on endpoints uses `@PreAuthorize`. */
    fun roleOrNull(): String? = jwtOrNull()?.getClaimAsString("role")

    fun hasRole(role: String): Boolean = roleOrNull() == role

    fun isAdmin(): Boolean = hasRole("ADMIN")
}
