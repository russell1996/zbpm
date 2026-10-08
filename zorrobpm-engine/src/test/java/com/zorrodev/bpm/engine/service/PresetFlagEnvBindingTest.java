package com.zorrodev.bpm.engine.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-VT-1 п.6 (урок C8-36 F-7): env-имя {@code ZORROBPM_UI_VARIABLE_PRESETS_ENABLED}
 * обязано доезжать до флага через relaxed binding, а ключ — лежать в ОБОИХ
 * файлах (rest-properties перекрывается app-файлом в fat-jar).
 *
 * <p>Мутации, которые обязаны ронять этот тест: переименовать env-имя;
 * убрать ключ из app-файла; убрать {@code @Value} с флага.
 */
class PresetFlagEnvBindingTest {

    private static final String KEY = "zorrobpm.ui.variable-presets.enabled";
    private static final String ENV = "ZORROBPM_UI_VARIABLE_PRESETS_ENABLED";

    @Configuration
    static class FlagProbe {
        @Value("${zorrobpm.ui.variable-presets.enabled:true}")
        boolean enabled;
    }

    private static ApplicationContextRunner runnerWithContainerEnv(Map<String, String> containerEnv) {
        Map<String, Object> asObjects = new LinkedHashMap<>(containerEnv);
        return new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("containerEnv", asObjects)))
            .withUserConfiguration(FlagProbe.class);
    }

    @Test
    void envFalse_disablesFlag() {
        runnerWithContainerEnv(Map.of(ENV, "false")).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(FlagProbe.class).enabled)
                .as("%s=false обязан выключать флаг (relaxed binding)", ENV)
                .isFalse();
        });
    }

    @Test
    void default_isEnabled() {
        runnerWithContainerEnv(Map.of()).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(FlagProbe.class).enabled)
                .as("дефолт флага — true (п.6 WO)")
                .isTrue();
        });
    }

    @Test
    void key_presentInBothPropertiesFiles() throws Exception {
        Path cwd = Path.of("").toAbsolutePath();
        // CWD surefire — каталог модуля (zorrobpm-engine), корень репо — на уровень выше;
        // если гоняют из корня — он сам.
        Path root = Files.exists(cwd.resolve("zorrobpm-rest"))
            ? cwd : cwd.getParent();
        String restProps = Files.readString(
            root.resolve("zorrobpm-rest/src/main/resources/zorrobpm-rest.properties"));
        String appProps = Files.readString(
            root.resolve("zorrobpm-app/src/main/resources/application.properties"));
        assertThat(restProps).as("ключ в rest-файле").contains(KEY);
        assertThat(appProps).as("ключ в app-файле (F-7: иначе в fat-jar потеряется)").contains(KEY);
        assertThat(restProps).contains(ENV);
        assertThat(appProps).contains(ENV);
    }
}
