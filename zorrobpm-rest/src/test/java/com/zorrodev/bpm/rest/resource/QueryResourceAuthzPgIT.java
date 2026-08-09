package com.zorrodev.bpm.rest.resource;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * WO-TEST-3 (T-02): the WO-SEC-43 direct-GET cross-grant isolation suite
 * ({@link QueryResourceAuthzIntegrationTest}) executed against a REAL PostgreSQL
 * (V11 / P-22: H2-green is not PG-green — H2 hides missing columns, FK constraints
 * and driver quirks). Inherits every @Test from the H2 variant, so the two suites
 * cannot drift; each principal uses a real grant created via the admin API chain.
 *
 * PG wiring mirrors zorrobpm-engine PostgresIT: PG_HOST/PG_PORT/PG_DB/PG_USER/PG_PASSWORD
 * env vars (or -D), defaulting to ci/docker-compose.pg.yml. Runs only in the "pg" group:
 * {@code mvn verify -pl zorrobpm-rest -am -Dgroups=pg -Dzbpm.excludedGroups=} against a
 * running postgres:16 (see mimo-standing-prompt §1c). Excluded from the default H2 verify
 * via rest failsafe excludedGroups (mirroring zorrobpm-engine).
 */
@Tag("pg")
@ActiveProfiles("test")
public class QueryResourceAuthzPgIT extends QueryResourceAuthzIntegrationTest {

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
