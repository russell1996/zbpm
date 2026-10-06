package com.zorrodev.bpm.rest.resource;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * WO-IN-2 criterion 4 on a REAL PostgreSQL (V11 / P-17 / P-22): the {@code relatesTo}
 * authorization boundary inherited from {@link UserTaskRelatesToAuthzIntegrationTest}, so the two
 * suites cannot drift. Authorization is exactly the class of defect that an H2-green run does not
 * rule out — the point of the boundary is WHICH rows a query returns, and that is decided by the
 * planner and the real column contents.
 *
 * <p>PG wiring mirrors zorrobpm-engine's {@code PostgresIT}: PG_HOST/PG_PORT/PG_DB/PG_USER/
 * PG_PASSWORD env vars (or -D), defaulting to ci/docker-compose.pg.yml. Runs only in the "pg"
 * group ({@code mvn verify -pl zorrobpm-rest -am -Dgroups=pg -Dzbpm.excludedGroups=}), excluded
 * from the default H2 verify via the rest failsafe excludedGroups.
 */
@Tag("pg")
@ActiveProfiles("test")
public class UserTaskRelatesToAuthzPgIT extends UserTaskRelatesToAuthzIntegrationTest {

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