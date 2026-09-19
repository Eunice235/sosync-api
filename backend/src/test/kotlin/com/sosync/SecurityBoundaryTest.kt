package com.sosync

import com.sosync.domain.AccountStatus
import com.sosync.domain.Role
import com.sosync.service.auth.JwtService
import com.sosync.support.IntegrationTest
import com.sosync.support.TestFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/**
 * The filter chain, driven through the whole stack.
 *
 * MockMvc rather than assertions on the configuration object, because the failure this guards
 * against is a path that quietly stops being protected. A test of the config would still pass
 * in that case; a request that comes back 200 when it should be 403 would not.
 */
@AutoConfigureMockMvc
class SecurityBoundaryTest : IntegrationTest() {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jwtService: JwtService

    private fun statusOf(path: String, token: String? = null): Int {
        val request = get(path)
        token?.let { request.header("Authorization", "Bearer $it") }
        return mockMvc.perform(request).andReturn().response.status
    }

    @Test
    @DisplayName("protected endpoints refuse an anonymous caller")
    fun anonymousIsRefused() {
        assertThat(statusOf("/api/incidents/active")).isEqualTo(401)
        assertThat(statusOf("/api/me/contacts")).isEqualTo(401)
        assertThat(statusOf("/api/notifications")).isEqualTo(401)
        assertThat(statusOf("/api/responder/alerts")).isEqualTo(401)
        assertThat(statusOf("/api/admin/stats")).isEqualTo(401)
    }

    @Test
    @DisplayName("docs, health, plans and metadata are open, so a judge can look without a token")
    fun publicSurfaceIsReachable() {
        assertThat(statusOf("/actuator/health")).isEqualTo(200)
        assertThat(statusOf("/api/plans")).isEqualTo(200)
        assertThat(statusOf("/api/meta/info")).isEqualTo(200)
        assertThat(statusOf("/api/meta/languages")).isEqualTo(200)
    }

    @Test
    @DisplayName("an ordinary user cannot reach responder or admin endpoints")
    fun userCannotReachPrivilegedEndpoints() {
        val token = jwtService.issue(fixtures.user()).accessToken

        // Their own surface works: 204 because they have no emergency in progress.
        assertThat(statusOf("/api/incidents/active", token)).isEqualTo(204)
        assertThat(statusOf("/api/me/contacts", token)).isEqualTo(200)

        assertThat(statusOf("/api/responder/alerts", token)).isEqualTo(403)
        assertThat(statusOf("/api/admin/stats", token)).isEqualTo(403)
        assertThat(statusOf("/api/admin/users", token)).isEqualTo(403)
    }

    @Test
    @DisplayName("a responder can reach the responder surface but not administration")
    fun responderCannotReachAdmin() {
        val (account, _) = fixtures.responder()
        val token = jwtService.issue(account).accessToken

        assertThat(statusOf("/api/responder/alerts", token)).isEqualTo(200)

        // A responder verifying themselves would remove the trust boundary entirely.
        assertThat(statusOf("/api/admin/responders", token)).isEqualTo(403)
        assertThat(statusOf("/api/admin/stats", token)).isEqualTo(403)
    }

    @Test
    @DisplayName("an admin reaches administration")
    fun adminHasAccess() {
        val token = jwtService.issue(fixtures.user(role = Role.ADMIN)).accessToken

        assertThat(statusOf("/api/admin/stats", token)).isEqualTo(200)
        assertThat(statusOf("/api/admin/users", token)).isEqualTo(200)
        assertThat(statusOf("/api/admin/responders", token)).isEqualTo(200)
    }

    @Test
    @DisplayName("a refresh token is not accepted as an access token")
    fun refreshTokenIsNotAnAccessToken() {
        val tokens = jwtService.issue(fixtures.user(role = Role.ADMIN))

        assertThat(statusOf("/api/admin/stats", tokens.accessToken)).isEqualTo(200)

        // Both are signed with the same symmetric key, so the signature alone cannot tell them
        // apart. Without the `typ` validator on the decoder, this 30-day credential would
        // authenticate every request exactly like the 24-hour one, and the shorter lifetime
        // would be decoration.
        assertThat(statusOf("/api/admin/stats", tokens.refreshToken)).isEqualTo(401)
        assertThat(statusOf("/api/incidents/active", tokens.refreshToken)).isEqualTo(401)
    }

    @Test
    @DisplayName("a refresh token cannot be exchanged by passing an access token instead")
    fun accessTokenCannotBeUsedToRefresh() {
        val tokens = jwtService.issue(fixtures.user())

        val status = mockMvc.perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"${tokens.accessToken}"}"""),
        ).andReturn().response.status

        // Without the `typ` check, a short-lived credential would quietly become a long-lived
        // one by being handed back here.
        assertThat(status).isEqualTo(401)
    }

    @Test
    @DisplayName("a garbage or tampered token is refused")
    fun invalidTokenIsRefused() {
        assertThat(statusOf("/api/incidents/active", "not-a-token")).isEqualTo(401)

        val valid = jwtService.issue(fixtures.user()).accessToken
        val tampered = valid.dropLast(6) + "abcdef"
        assertThat(statusOf("/api/incidents/active", tampered)).isEqualTo(401)
    }

    @Test
    @DisplayName("a suspended account cannot sign in, so its access ends at the next token")
    fun suspendedAccountCannotLogIn() {
        val suspended = fixtures.user(status = AccountStatus.SUSPENDED)

        val status = mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"identifier":"${suspended.phone}","password":"${TestFixtures.PASSWORD}"}""",
                ),
        ).andReturn().response.status

        assertThat(status).isEqualTo(403)
    }

    @Test
    @DisplayName("login says the same thing for a wrong password and a number with no account")
    fun loginDoesNotRevealWhoHasAnAccount() {
        val existing = fixtures.user()

        fun body(identifier: String, password: String) = mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"$identifier","password":"$password"}"""),
        ).andReturn().response.let { it.status to it.contentAsString }

        val wrongPassword = body(existing.phone, "WrongPass#2026")
        val noSuchAccount = body("+254799999999", "WrongPass#2026")

        // Distinguishing them would confirm which numbers have accounts, which for a personal
        // safety app leaks who uses one. Compared with the timestamp stripped, since the two
        // responses are milliseconds apart and that difference carries no information.
        fun withoutTimestamp(body: String) =
            body.replace(Regex(",\\s*\"timestamp\"\\s*:\\s*\"[^\"]+\""), "")

        assertThat(wrongPassword.first).isEqualTo(401)
        assertThat(noSuchAccount.first).isEqualTo(401)
        assertThat(withoutTimestamp(wrongPassword.second))
            .isEqualTo(withoutTimestamp(noSuchAccount.second))
    }

    @Test
    @DisplayName("an administrator account cannot be created by registering as one")
    fun adminCannotSelfRegister() {
        val status = mockMvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"fullName":"Sneaky","phone":"+254798888888",
                     "password":"Sneaky#2026","role":"ADMIN"}
                    """.trimIndent(),
                ),
        ).andReturn().response.status

        assertThat(status).isEqualTo(403)
    }
}
