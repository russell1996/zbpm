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

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        String host = System.getProperty("PG_HOST", "127.0.0.1");
        String port = System.getProperty("PG_PORT", "55432");
        String db = System.getProperty("PG_DB", "zbpm_test");
        String user = System.getProperty("PG_USER", "postgres");
        String pass = System.getProperty("PG_PASSWORD", "postgres");

        registry.add("spring.datasource.url",
            () -> "jdbc:postgresql://" + host + ":" + port + "/" + db + "?sslmode=disable");
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> pass);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.liquibase.enabled", () -> "true");
        registry.add("spring.hikari.connection-timeout", () -> "60000");
    }
}
