package com.zorrodev.bpm.rest.resource;

import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * WO-REL-22 (B2): the admin re-drive flow ({@link OutboxRedriveIntegrationTest})
 * executed against a REAL PostgreSQL (V11: H2-green is not PG-green).
 * Inherits every @Test from the H2 variant, so the two suites cannot drift.
 *
 * PG wiring mirrors zorrobpm-engine PostgresIT. Runs only in the "pg" group.
 * Excluded from the default H2 verify via rest failsafe excludedGroups.
 */
@Tag("pg")
@ActiveProfiles("test")
@Import(OutboxRedriveIntegrationTest.CaptureConfig.class)
public class OutboxRedrivePgIT extends OutboxRedriveIntegrationTest {

    @DynamicPropertySource
    static void pgProperties(DynamicPropertyRegistry registry) {
        String host = cfg("PG_HOST", "127.0.0.1");
        String port = cfg("PG_PORT", "5432");
        String db = cfg("PG_DB", "zorrobpm-db");
        String user = cfg("PG_USER", "zorrodev");
        String pass = cfg("PG_PASSWORD", "zorrodev");

        registry.add("spring.datasource.url",
            () -> "jdbc:postgresql://" + host + ":" + port + "/" + db + "?sslmode=disable");
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> pass);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.liquibase.enabled", () -> "true");
        registry.add("spring.hikari.connection-timeout", () -> "60000");
    }

    private static String cfg(String key, String dflt) {
        String v = System.getenv(key);
        if (v == null) v = System.getProperty(key);
        return v != null ? v : dflt;
    }
}
