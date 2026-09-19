package com.sosync.support

import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Duration

/**
 * Base for tests that need the real thing.
 *
 * A genuine PostgreSQL container rather than an in-memory substitute, because several of the
 * rules being tested are enforced by the database: the partial unique index that stops one
 * emergency becoming three exists only in PostgreSQL, and an H2 run would pass while the real
 * constraint went untested. Flyway runs against the container too, so the migrations are
 * exercised on every build.
 *
 * ### Why the container is started by hand
 *
 * The obvious spelling — `@Testcontainers` with an `@Container` field — is wrong here, and
 * wrong in a way that looks like a code bug. JUnit stops an `@Container` after the class that
 * declared it, but Spring caches the application context *across* classes. The first test class
 * passes, its container is destroyed, and every class after it inherits a cached context whose
 * connection pool points at a database that no longer exists. The symptom is 30-second Hikari
 * timeouts in `resetDatabase`, nowhere near the actual mistake.
 *
 * So: one container, started once, never stopped. The JVM exiting ends it, and Testcontainers'
 * Ryuk sidecar removes it even if the JVM is killed. As a bonus the suite stops paying a
 * container start per class, which on Docker Desktop was most of its runtime.
 *
 * Tests deliberately do **not** run inside a rolled-back transaction. The behaviour under test
 * includes constraint violations and what the service does after catching one, and a test
 * transaction marked rollback-only behaves differently from a real one. Tables are truncated
 * between tests instead.
 */
@SpringBootTest(
    properties = [
        // The demo cast would collide with fixtures and skew the assertions.
        "sosync.demo.seed=false",
    ],
)
abstract class IntegrationTest {

    @Autowired
    protected lateinit var jdbc: JdbcTemplate

    @Autowired
    protected lateinit var fixtures: TestFixtures

    @BeforeEach
    fun resetDatabase() {
        // CASCADE because incidents, notifications and the location trail all reference users.
        // flyway_schema_history is left alone: truncating it would make the next context reuse
        // re-run every migration.
        jdbc.execute(
            """
            TRUNCATE TABLE
                audit_log, notifications, incident_events, location_updates, incidents,
                subscriptions, family_members, families, emergency_contacts, responders,
                verification_codes, users
            CASCADE
            """.trimIndent(),
        )
    }

    companion object {
        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:17-alpine")
                // The default is 60 seconds, which is marginal on Docker Desktop: Postgres
                // logs "ready to accept connections" twice during first-time initialisation,
                // and the wait strategy needs both. When it times out, every test in the suite
                // fails with a NoClassDefFoundError from this initialiser, which looks nothing
                // like the slow container start that actually caused it.
                .withStartupTimeout(Duration.ofMinutes(3))
                .apply { start() }

        /**
         * Feeds the container's mapped port into every context, so no test needs to know it.
         * Property suppliers rather than values, because the container is not started until
         * this class is first loaded.
         */
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
