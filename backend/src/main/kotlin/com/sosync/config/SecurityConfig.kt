package com.sosync.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import com.sosync.service.auth.JwtService
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import javax.crypto.spec.SecretKeySpec

/**
 * Turns the `roles` claim into Spring Security authorities.
 *
 * Roles are read off the token and nothing else. There is no database lookup per request: a
 * role change takes effect when the user next obtains a token, which is the same trade-off the
 * Readers resource servers make.
 */
class SosyncJwtAuthConverter : Converter<Jwt, AbstractAuthenticationToken> {
    override fun convert(jwt: Jwt): AbstractAuthenticationToken {
        val authorities: List<GrantedAuthority> =
            jwt.getClaimAsStringList("roles").orEmpty().map { SimpleGrantedAuthority(it) }
        return JwtAuthenticationToken(jwt, authorities, jwt.subject)
    }
}

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig(private val properties: SosyncProperties) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain =
        http
            // Stateless bearer-token API: there is no session cookie for CSRF to protect.
            .csrf { it.disable() }
            .cors { it.configurationSource(corsConfigurationSource()) }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { authorize ->
                authorize
                    .requestMatchers(*PUBLIC_PATHS).permitAll()
                    .requestMatchers("/api/admin/**").hasRole("ADMIN")
                    .requestMatchers("/api/responder/**").hasAnyRole("RESPONDER", "ADMIN")
                    .anyRequest().authenticated()
            }
            .oauth2ResourceServer { oauth2 ->
                oauth2.jwt { jwt ->
                    jwt.decoder(jwtDecoder())
                    jwt.jwtAuthenticationConverter(SosyncJwtAuthConverter())
                }
            }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .exceptionHandling {
                // JSON, not Spring's HTML error page: every caller here is an app.
                it.authenticationEntryPoint { _, response, _ ->
                    response.status = 401
                    response.contentType = "application/json"
                    response.writer.write(
                        """{"error":"unauthorized","message":"Authentication required"}""",
                    )
                }
                it.accessDeniedHandler { _, response, _ ->
                    response.status = 403
                    response.contentType = "application/json"
                    response.writer.write(
                        """{"error":"forbidden","message":"You do not have permission to do that"}""",
                    )
                }
            }
            .build()

    /**
     * Verifies tokens this service issued.
     *
     * The `typ` validator matters more than it looks. Access and refresh tokens are signed with
     * the same symmetric key, so a signature check alone cannot tell them apart: without this,
     * a 30-day refresh token would authenticate every request exactly like a 24-hour access
     * token, and the shorter lifetime would be decoration.
     *
     * The issuer check is cheap and closes the case where the same secret is reused by another
     * service in a shared environment.
     */
    @Bean
    fun jwtDecoder(): JwtDecoder {
        val decoder = NimbusJwtDecoder
            .withSecretKey(SecretKeySpec(properties.jwt.secret.toByteArray(), "HmacSHA256"))
            .macAlgorithm(MacAlgorithm.HS256)
            .build()

        decoder.setJwtValidator(
            DelegatingOAuth2TokenValidator(
                // Expiry and not-before.
                JwtValidators.createDefaultWithIssuer(properties.jwt.issuer),
                JwtClaimValidator<String>(JwtService.CLAIM_TOKEN_TYPE) {
                    it == JwtService.TOKEN_TYPE_ACCESS
                },
            ),
        )
        return decoder
    }

    /**
     * BCrypt for both passwords and safety PINs. A four-digit PIN is trivially brute-forced
     * offline whatever the hash, so the real protection is that the PIN is only ever checked
     * server-side against an active incident; the hash keeps a database leak from handing over
     * the PINs directly.
     */
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val config = CorsConfiguration().apply {
            // Wide open because the clients are a Flutter app and a local dashboard, neither of
            // which has a stable origin during development. Narrow this before deploying.
            allowedOriginPatterns = listOf("*")
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf("*")
            allowCredentials = true
            maxAge = 3600
        }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/**", config)
        }
    }

    companion object {
        /**
         * Unauthenticated surface. Registration and login obviously, plus the docs and health
         * endpoints so a judge can open Swagger without a token.
         */
        private val PUBLIC_PATHS = arrayOf(
            "/api/auth/register",
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/auth/verify-phone",
            "/api/auth/forgot-password",
            "/api/auth/reset-password",
            "/api/plans",
            "/api/meta/**",
            "/docs",
            "/docs/**",
            "/swagger-ui/**",
            "/api-docs",
            "/api-docs/**",
            "/actuator/health",
            "/actuator/info",
        )
    }
}
