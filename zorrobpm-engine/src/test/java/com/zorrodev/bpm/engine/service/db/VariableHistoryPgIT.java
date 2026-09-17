package com.zorrodev.bpm.engine.service.db;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * WO-ENG-16 (WB-003): та же история переменных на реальном PostgreSQL (V11:
 * H2-зелёный ≠ прод-зелёный). Наследует все тесты базового класса без
 * изменений — сюиты не дрейфуют. Заодно доказывает миграцию 110 (G-N/G9):
 * Liquibase применяет changeset при старте контекста, тест идёт через
 * прод-путь — удали changeset, и тест упадёт на отсутствующей таблице.
 */
@Tag("pg")
@ActiveProfiles({"test", "pgtest"})
public class VariableHistoryPgIT extends VariableHistoryIntegrationTests {

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
