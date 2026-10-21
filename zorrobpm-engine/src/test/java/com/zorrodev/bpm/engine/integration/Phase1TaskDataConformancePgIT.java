package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.engine.TestMain;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;

/**
 * WO-OPS-28 ФАЗА 1 — тот же корпус «Задачи и данные», но на ЖИВОМ PostgreSQL 16 (V11/P-17:
 * H2 ≠ PostgreSQL). Цель — отделить артефакты H2 от поведения движка и прогнать инвариант
 * утечек G-P на настоящей СУБД.
 *
 * <p>Запуск — по раннбуку {@code docs/internal} (команда
 * {@code ci/run-pg-tests.sh}): контейнер {@code postgres:16}, сеть compose, креды через env.
 * Свой контейнер не поднимается; чужие БД/контейнеры не трогаются.</p>
 *
 * <p>Наблюдения пишутся отдельным файлом ({@code target/ops28-phase1-observed-pg.json}),
 * чтобы не затирать H2-снимок того же корпуса.</p>
 */
@Tag("pg")
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles({"test", "pgtest"})
public class Phase1TaskDataConformancePgIT extends Phase1TaskDataConformanceSupport {

    /** Те же параметры подключения, что у PostgresIT (env → system property → дефолт compose). */
    private static String cfg(String key, String dflt) {
        String v = System.getenv(key);
        if (v == null) {
            v = System.getProperty(key);
        }
        return v != null ? v : dflt;
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://"
            + cfg("PG_HOST", "127.0.0.1") + ":" + cfg("PG_PORT", "5432") + "/"
            + cfg("PG_DB", "zorrobpm-db") + "?sslmode=disable");
        registry.add("spring.datasource.username", () -> cfg("PG_USER", "zorrodev"));
        registry.add("spring.datasource.password", () -> cfg("PG_PASSWORD", "zorrodev"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.liquibase.enabled", () -> "true");
        registry.add("spring.hikari.connection-timeout", () -> "60000");
    }

    @Override
    protected String observedFile() {
        return "target/ops28-phase1-observed-pg.json";
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void measure(Map<String, Object> c) throws Exception {
        measureCase(c);
    }
}
