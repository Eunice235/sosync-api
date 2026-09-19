package com.sosync.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Contact
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Swagger UI at `/docs` is the second deliverable of this service: it is the API documentation,
 * and it is also the console the demo is driven from when the mobile app is not the thing being
 * shown.
 */
@Configuration
class OpenApiConfig {

    @Bean
    fun openApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("SOSync API")
                .version("0.1.0")
                .description(
                    """
                    Discreet emergency reporting and rapid response.

                    One action raises an incident: the reporter holds the SOS button, the service
                    captures GPS, alerts their trusted contacts and nearby verified responders,
                    and keeps the location updating until somebody closes the incident.

                    **How to use this page**
                    1. `POST /api/auth/login` with one of the seeded demo accounts.
                    2. Copy `accessToken` from the response.
                    3. Press **Authorize** above and paste it.
                    4. Work through the SOS flow under *Incidents*, then *Responder*.

                    Seeded demo accounts (password `Sosync#2026` for all of them):

                    | Role | Phone | Who |
                    |---|---|---|
                    | USER | `+254712000001` | Amina, the reporter |
                    | USER | `+254712000002` | Grace, her sister and trusted contact |
                    | RESPONDER | `+254712000003` | Daniel, verified guard |
                    | ADMIN | `+254712000009` | Platform operator |

                    **Prototype limits.** No real SMS or push gateway is connected: deliveries
                    are recorded with status `SIMULATED` and returned over the API so you can
                    see exactly what each recipient would have received. Subscription state is
                    simulated and nothing on the emergency path consults it. This is not
                    connected to any national emergency service.
                    """.trimIndent(),
                )
                .contact(Contact().name("SOSync").email("hello@sosync.app"))
                .license(License().name("Hackathon prototype")),
        )
        // No explicit server entry: springdoc derives it from the request, so "Try it out" calls
        // whichever host the page was opened on - localhost in development, the server's own
        // address when hosted. A hardcoded localhost made every hosted request go to the
        // viewer's own machine.
        .components(
            Components().addSecuritySchemes(
                BEARER,
                SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("Paste the accessToken from /api/auth/login. No 'Bearer' prefix."),
            ),
        )
        .addSecurityItem(SecurityRequirement().addList(BEARER))

    companion object {
        const val BEARER = "bearerAuth"
    }
}
