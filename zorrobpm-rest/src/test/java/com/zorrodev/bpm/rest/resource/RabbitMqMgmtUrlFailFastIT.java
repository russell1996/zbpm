package com.zorrodev.bpm.rest.resource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-INT-9 (G-C.3, P-41): fail-fast валидация
 * {@code zorrobpm.rabbitmq.management.base-url} — тот же класс, что
 * {@link DbRabbitPasswordFailFastIT} (полный контекст, профили).
 * Невалидный URL невалиден везде (провижининг слал бы креды не туда),
 * поэтому проверка безусловна — без SAFE-профилей.
 */
class RabbitMqMgmtUrlFailFastIT {

    private static ApplicationContextInitializer<ConfigurableApplicationContext> isolatedDb(String dbName) {
        return ctx -> ctx.getEnvironment().getPropertySources().addFirst(
            new MapPropertySource("int9-isolated-db", Map.of(
                "spring.datasource.url", "jdbc:h2:mem:" + dbName,
                "spring.datasource.driver-class-name", "org.h2.Driver")));
    }

    private static Map<String, Object> baseProps() {
        // Сильные соседние секреты — падать обязан ТОЛЬКО MgmtUrl-валидатор,
        // иначе TokenService/AdminPassword/DbRabbit fail-fast замаскируют причину.
        Map<String, Object> m = new HashMap<>();
        m.put("server.port", "0");
        m.put("spring.rabbitmq.host", "localhost");
        m.put("spring.rabbitmq.password", "Int9Str0ng!RabbitPass");
        m.put("spring.datasource.password", "Int9Str0ng!DbPass");
        m.put("spring.liquibase.enabled", "false");
        m.put("zorrobpm.security.jwt-secret", "int9-jwt-secret-not-default-0123456789");
        m.put("zorrobpm.security.default-admin-password", "Int9Str0ng!AdminPass");
        return m;
    }

    /**
     * WO-QW-4: URL — через initializer с addFirst (высший приоритет).
     * {@code SpringApplicationBuilder.properties(...)} — это DEFAULT-properties
     * (низший приоритет): тестовый {@code application.properties} задаёт
     * валидный base-url, и default-properties его не перебивают — валидатор
     * видел бы `http://localhost:15672` из файла, а не тестовое значение.
     */
    private static ApplicationContextInitializer<ConfigurableApplicationContext> mgmtUrl(String url) {
        return ctx -> ctx.getEnvironment().getPropertySources().addFirst(
            new MapPropertySource("int9-mgmt-url", Map.of(
                "zorrobpm.rabbitmq.management.base-url", url)));
    }

    @Test
    void testProfile_withNonHttpBaseUrl_shouldFailFast() {
        assertThatThrownBy(() -> {
            ConfigurableApplicationContext ctx = new SpringApplicationBuilder(TestMain.class)
                .web(WebApplicationType.SERVLET)
                .profiles("test")
                .initializers(isolatedDb("int9-mgmt-fail"), mgmtUrl("ftp://broker:15672"))
                .properties(baseProps())
                .run();
            ctx.close();
        }).satisfies(ex -> {
            Throwable root = ex;
            while (root.getCause() != null) root = root.getCause();
            assertThat(root).isInstanceOf(IllegalStateException.class);
            assertThat(root.getMessage()).contains("zorrobpm.rabbitmq.management.base-url");
        });
    }

    @Test
    void testProfile_withMissingHost_shouldFailFast() {
        assertThatThrownBy(() -> {
            ConfigurableApplicationContext ctx = new SpringApplicationBuilder(TestMain.class)
                .web(WebApplicationType.SERVLET)
                .profiles("test")
                .initializers(isolatedDb("int9-mgmt-nohost"), mgmtUrl("http://"))
                .properties(baseProps())
                .run();
            ctx.close();
        }).satisfies(ex -> {
            Throwable root = ex;
            while (root.getCause() != null) root = root.getCause();
            assertThat(root).isInstanceOf(IllegalStateException.class);
            assertThat(root.getMessage()).contains("zorrobpm.rabbitmq.management.base-url");
        });
    }

    @Test
    void testProfile_withDefaultBaseUrl_shouldStart() {
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(TestMain.class)
            .web(WebApplicationType.SERVLET)
            .profiles("test")
            .initializers(isolatedDb("int9-mgmt-ok"))
            .properties(baseProps())
            .run();
        try {
            assertThat(ctx.isRunning()).isTrue();
        } finally {
            ctx.close();
        }
    }
}
