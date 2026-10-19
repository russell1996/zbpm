package com.zorrodev.bpm.rest.resource;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * WO-C8-51 (часть A): тот же набор, что {@link QueryResourceVariablesByScopeIntegrationTests},
 * но на РЕАЛЬНОМ PostgreSQL (V11 / P-22). Наследует каждый {@code @Test} — наборы
 * не расходятся. Проверяются именно PG-специфичные места нового эндпоинта:
 * группировка по {@code scope_id} (nullable UUID), {@code findAllById} по
 * activities/tokens, порядок {@code createdAt}, отсутствие H2-коэрсинга типов.
 *
 * PG wiring mirrors QueryResourceAuthzPgIT / zorrobpm-engine PostgresIT:
 * PG_HOST/PG_PORT/PG_DB/PG_USER/PG_PASSWORD env vars (or -D), defaulting to
 * ci/docker-compose.pg.yml. Runs only in the "pg" group; excluded from the
 * default H2 verify via rest failsafe excludedGroups.
 */
@Tag("pg")
@ActiveProfiles("test")
public class QueryResourceVariablesByScopePgIT extends QueryResourceVariablesByScopeIntegrationTests {

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
