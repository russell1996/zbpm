package com.zorrodev.bpm.engine.retention;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-AUDIT-7: ручки, введённые этим WO, обязаны ДОЕЗЖАТЬ до бина из
 * задеплоенного приложения (урок WO-C8-36 F-7: укороченное env-имя без
 * ENGINE_/RETENTION_ не разрешается никогда).
 *
 * <p>Мутации, которые обязаны ронять этот тест: переименовать ключ в
 * properties без обновления имени переменной; переименовать поле в
 * {@code RetentionConfig}; подменить имя переменной в тесте на укороченное.
 */
class Audit7RetentionEnvBindingTest {

    /** «ключ свойства → имя переменной» (точная relaxed-binding форма ключа). */
    private static final Map<String, String> KEY_TO_ENV = Map.of(
        "zorrobpm.engine.retention.events-ttl-days", "ZORROBPM_ENGINE_RETENTION_EVENTS_TTL_DAYS",
        "zorrobpm.engine.retention.outbox-ttl-days", "ZORROBPM_ENGINE_RETENTION_OUTBOX_TTL_DAYS",
        "zorrobpm.engine.retention.dry-run", "ZORROBPM_ENGINE_RETENTION_DRY_RUN",
        "zorrobpm.engine.retention.batch-pause-ms", "ZORROBPM_ENGINE_RETENTION_BATCH_PAUSE_MS");

    /**
     * Имена переменных берутся НЕ из константы выше, а из самого
     * {@code zorrobpm-engine.properties} (тот файл, который читает прод):
     * тест проверяет, что placeholder в файле — ровно relaxed-форма ключа.
     * Расхождение файла и кода уронит тест здесь, а не в проде.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "zorrobpm.engine.retention.events-ttl-days, ZORROBPM_ENGINE_RETENTION_EVENTS_TTL_DAYS",
        "zorrobpm.engine.retention.outbox-ttl-days, ZORROBPM_ENGINE_RETENTION_OUTBOX_TTL_DAYS",
        "zorrobpm.engine.retention.dry-run, ZORROBPM_ENGINE_RETENTION_DRY_RUN",
        "zorrobpm.engine.retention.batch-pause-ms, ZORROBPM_ENGINE_RETENTION_BATCH_PAUSE_MS",
    })
    void engineProperties_declaresKeyUnderItsRelaxedBindingEnvName(String key, String env) {
        String properties = read(repoRoot().resolve(
            "zorrobpm-engine/src/main/resources/zorrobpm-engine.properties"));
        assertThat(properties)
            .as("ключ %s обязан быть объявлен в zorrobpm-engine.properties под env-именем %s "
                + "(WO-C8-36 F-7: укороченное имя не разрешается)", key, env)
            .contains(key + "=${" + env + ":");
    }

    @Test
    void containerEnvValues_reachRetentionConfigBean() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("ZORROBPM_ENGINE_RETENTION_EVENTS_TTL_DAYS", "45");
        env.put("ZORROBPM_ENGINE_RETENTION_OUTBOX_TTL_DAYS", "60");
        env.put("ZORROBPM_ENGINE_RETENTION_DRY_RUN", "true");
        env.put("ZORROBPM_ENGINE_RETENTION_BATCH_PAUSE_MS", "50");

        runnerWithContainerEnv(env).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            RetentionConfig config = ctx.getBean(RetentionConfig.class);
            // Сентинели: ни один не совпадает с дефолтом (0/0/false/0) — иначе
            // ассерт прошёл бы и при неработающей связке.
            assertThat(config.getEventsTtlDays()).isEqualTo(45);
            assertThat(config.getOutboxTtlDays()).isEqualTo(60);
            assertThat(config.isDryRun()).isTrue();
            assertThat(config.getBatchPauseMs()).isEqualTo(50L);
        });
    }

    @Test
    void shortenedEnvName_doesNotResolve() {
        // Контроль-негатив F-7: укороченное имя (без ENGINE_) ключ не питает.
        Map<String, String> env = new LinkedHashMap<>();
        env.put("ZORROBPM_RETENTION_EVENTS_TTL_DAYS", "45");

        runnerWithContainerEnv(env).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(RetentionConfig.class).getEventsTtlDays())
                .as("укороченное имя обязано НЕ доезжать (остаётся дефолт 0)")
                .isEqualTo(0);
        });
    }

    private static ApplicationContextRunner runnerWithContainerEnv(Map<String, String> containerEnv) {
        Map<String, Object> asObjectMap = new LinkedHashMap<>(containerEnv);
        return new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("containerEnv", asObjectMap)))
            .withUserConfiguration(Audit7Beans.class);
    }

    static class Audit7Beans {
        @Bean
        RetentionConfig retentionConfig(
                @org.springframework.beans.factory.annotation.Value(
                    "${zorrobpm.engine.retention.events-ttl-days:0}") int eventsTtlDays,
                @org.springframework.beans.factory.annotation.Value(
                    "${zorrobpm.engine.retention.outbox-ttl-days:0}") int outboxTtlDays,
                @org.springframework.beans.factory.annotation.Value(
                    "${zorrobpm.engine.retention.dry-run:false}") boolean dryRun,
                @org.springframework.beans.factory.annotation.Value(
                    "${zorrobpm.engine.retention.batch-pause-ms:0}") long batchPauseMs) {
            RetentionConfig config = new RetentionConfig();
            config.setEventsTtlDays(eventsTtlDays);
            config.setOutboxTtlDays(outboxTtlDays);
            config.setDryRun(dryRun);
            config.setBatchPauseMs(batchPauseMs);
            return config;
        }
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("zorrobpm-engine"))
                && Files.exists(dir.resolve("docker-compose.yml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("корень репозитория не найден");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("не прочитан " + path, e);
        }
    }
}
