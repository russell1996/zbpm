package com.zorrodev.bpm.engine;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for integration tests that require real PostgreSQL.
 * Connects to a local postgres:16 (docker-compose or standalone).
 *
 * Run locally:
 * <pre>
 * docker compose up -d postgres
 * mvn test -pl zorrobpm-engine -Dgroups=pg
 * docker compose down postgres
 * </pre>
 */
@Tag("pg")
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles({"test", "pgtest"})
public abstract class PostgresIT {

    // Resolve from ENV VARS first (a Surefire/Failsafe fork inherits the parent env reliably),
    // then Maven -D system properties (NOT always propagated to the fork), then a default that
    // matches ci/docker-compose.pg.yml. The sanctioned run (mimo-standing-prompt §1c) joins the
    // compose network and passes PG_HOST=postgres / PG_PORT=5432 via -e, so it never touches any
    // host-side PostgreSQL on 5432/5433 (P-23).
    private static String cfg(String key, String dflt) {
        String v = System.getenv(key);
        if (v == null) v = System.getProperty(key);
        return v != null ? v : dflt;
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
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
}
