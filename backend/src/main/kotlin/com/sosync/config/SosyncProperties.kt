package com.sosync.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue

/**
 * Everything tunable in one place, bound from the `sosync.*` block in application.yml.
 */
@ConfigurationProperties(prefix = "sosync")
data class SosyncProperties(
    val jwt: Jwt,
    val location: Location,
    val notifications: Notifications,
    val demo: Demo,
) {
    data class Jwt(
        /**
         * HS256 signing key. Symmetric on purpose: a single service signs and verifies its own
         * tokens, so the RSA keypair and JWKS endpoint the Readers auth server needs would be
         * machinery with nothing on the other end of it.
         */
        val secret: String,
        @DefaultValue("sosync") val issuer: String,
        /**
         * Deliberately long. A token that expires mid-incident forces a login at the worst
         * possible moment, and a refresh that fails on bad coverage is a failed rescue.
         */
        @DefaultValue("1440") val accessTokenMinutes: Long,
        @DefaultValue("30") val refreshTokenDays: Long,
    ) {
        init {
            // HS256 needs at least 256 bits of key. Nimbus throws a less obvious error later.
            require(secret.toByteArray().size >= 32) {
                "sosync.jwt.secret must be at least 32 characters"
            }
        }
    }

    data class Location(
        /**
         * How stale a responder position may be before that responder stops being alerted. A
         * responder whose last fix is hours old cannot be assumed to be anywhere near it.
         */
        @DefaultValue("30") val responderFreshnessMinutes: Long,
        @DefaultValue("15.0") val nearbyRadiusKm: Double,
    )

    data class Notifications(
        /**
         * When true, deliveries are recorded as SIMULATED and returned over the API instead of
         * being handed to a real gateway. The prototype ships this way, and the demo shows the
         * recorded payloads rather than claiming an SMS was sent.
         */
        @DefaultValue("true") val simulate: Boolean,
    )

    data class Demo(
        @DefaultValue("true") val seed: Boolean,
    )
}
