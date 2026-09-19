package com.sosync.service.auth

import com.nimbusds.jose.jwk.source.ImmutableSecret
import com.sosync.common.UnauthorizedException
import com.sosync.config.SosyncProperties
import com.sosync.domain.User
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.crypto.spec.SecretKeySpec

/** A freshly issued pair. The refresh token is the only way to obtain a new access token. */
data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
    val tokenType: String = "Bearer",
)

/**
 * Mints and verifies the service's own HS256 tokens.
 *
 * The Readers platform runs a full OAuth2 authorization server with an RSA keypair, because a
 * dozen independent resource servers have to verify tokens they did not issue. Here one service
 * both signs and verifies, so a symmetric key does the same job without a second deployable.
 *
 * The trade-off is real and worth stating: the signing key is also the verification key, so it
 * cannot be published, and rotating it invalidates every token at once.
 */
@Service
class JwtService(private val properties: SosyncProperties) {

    private val secretKey = SecretKeySpec(properties.jwt.secret.toByteArray(), "HmacSHA256")

    private val encoder = NimbusJwtEncoder(ImmutableSecret(secretKey))

    private val decoder = NimbusJwtDecoder
        .withSecretKey(secretKey)
        .macAlgorithm(MacAlgorithm.HS256)
        .build()

    fun issue(user: User): TokenPair {
        val accessSeconds = properties.jwt.accessTokenMinutes * 60
        return TokenPair(
            accessToken = mint(user, TOKEN_TYPE_ACCESS, accessSeconds),
            refreshToken = mint(
                user,
                TOKEN_TYPE_REFRESH,
                properties.jwt.refreshTokenDays * 24 * 60 * 60,
            ),
            expiresInSeconds = accessSeconds,
        )
    }

    /**
     * Validates a refresh token and returns the subject it belongs to.
     *
     * Refresh tokens are stateless, so this cannot detect one that has been stolen, and logout
     * cannot revoke one. A production build needs them persisted and rotated on use; that is
     * noted in the README rather than half-built here.
     */
    fun subjectFromRefreshToken(token: String): String {
        val jwt = try {
            decoder.decode(token)
        } catch (ex: JwtException) {
            throw UnauthorizedException("That refresh token is not valid")
        }

        // Without this check an access token would work as a refresh token, quietly turning a
        // short-lived credential into a long-lived one.
        if (jwt.getClaimAsString(CLAIM_TOKEN_TYPE) != TOKEN_TYPE_REFRESH) {
            throw UnauthorizedException("That is not a refresh token")
        }

        return jwt.subject ?: throw UnauthorizedException("That refresh token is not valid")
    }

    private fun mint(user: User, tokenType: String, lifetimeSeconds: Long): String {
        val now = Instant.now()
        val claims = JwtClaimsSet.builder()
            .issuer(properties.jwt.issuer)
            .subject(user.id)
            .issuedAt(now)
            .expiresAt(now.plus(lifetimeSeconds, ChronoUnit.SECONDS))
            .claim(CLAIM_TOKEN_TYPE, tokenType)
            .claim("role", user.role.name)
            // The prefixed form is what the authentication converter turns into authorities.
            .claim("roles", listOf(user.role.authority))
            .claim("name", user.fullName)
            .claim("phone", user.phone)
            .build()

        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        return encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
    }

    companion object {
        const val CLAIM_TOKEN_TYPE = "typ"
        const val TOKEN_TYPE_ACCESS = "access"
        const val TOKEN_TYPE_REFRESH = "refresh"
    }
}
