package com.zorrodev.bpm.engine;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for integration tests that require real PostgreSQL.
 *
 * In CI, a GitLab service (postgres:16) is available at host "postgres" port 5432.
 * Locally, set environment variables PG_HOST/PG_PORT/PG_DB/PG_USER/PG_PASSWORD
 * or start a local postgres on localhost:5432.
 *
 * Liquibase runs on startup against the real PG.
 */
@Tag("pg")
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles({"test", "pgtest"})
public abstract class PostgresIT {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        String host = System.getenv().getOrDefault("PG_HOST", "localhost");
        String port = System.getenv().getOrDefault("PG_PORT", "5432");
        String db = System.getenv().getOrDefault("PG_DB", "zbpm_test");
        String user = System.getenv().getOrDefault("PG_USER", "test");
        String pass = System.getenv().getOrDefault("PG_PASSWORD", "test");

        registry.add("spring.datasource.url", () ->
            "jdbc:postgresql://" + host + ":" + port + "/" + db);
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> pass);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.liquibase.enabled", () -> "true");
        registry.add("spring.hikari.connection-timeout", () -> "60000");
    }
}
