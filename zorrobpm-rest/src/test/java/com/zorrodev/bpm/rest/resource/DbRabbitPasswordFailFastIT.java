package com.zorrodev.bpm.rest.resource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-SEC-80 (F29): DB/RabbitMQ default-password fail-fast — тот же класс,
 * что {@link AdminPasswordFailFastIT} (полный контекст, дефолтные креды,
 * профили).
 */
class DbRabbitPasswordFailFastIT {

    private static ApplicationContextInitializer<ConfigurableApplicationContext> isolatedDb(String dbName) {
        return ctx -> ctx.getEnvironment().getPropertySources().addFirst(
            new MapPropertySource("sec80-isolated-db", Map.of(
                "spring.datasource.url", "jdbc:h2:mem:" + dbName,
                "spring.datasource.driver-class-name", "org.h2.Driver")));
    }

    /**
     * WO-QW-4: креды — через initializer с addFirst (высший приоритет).
     * {@code SpringApplicationBuilder.properties(...)} — это DEFAULT-properties
     * (низший приоритет): тестовый {@code application.properties} задаёт
     * rabbit-пароль, и default-properties его не перебивают (поймано живым
     * прогоном — валидатор видел `zorrodev` из файла, а не тестовое значение).
     */
    private static ApplicationContextInitializer<ConfigurableApplicationContext> creds(
            String dbPassword, String rabbitPassword) {
        return ctx -> ctx.getEnvironment().getPropertySources().addFirst(
            new MapPropertySource("sec80-creds", Map.of(
                "spring.datasource.password", dbPassword,
                "spring.rabbitmq.password", rabbitPassword)));
    }

    private static Map<String, Object> baseProps() {
        // Сильные соседние секреты — падать обязан ТОЛЬКО DbRabbit-валидатор,
        // иначе TokenService/AdminPassword fail-fast замаскируют причину.
        return Map.of(
            "server.port", "0",
            "spring.rabbitmq.host", "localhost",
            "spring.liquibase.enabled", "false",
            "zorrobpm.security.jwt-secret", "sec80-jwt-secret-not-default-0123456789",
            "zorrobpm.security.default-admin-password", "Sec80Str0ng!AdminPass");
    }

    private static Map<String, Object> with(Map<String, Object> base, String k, Object v) {
        java.util.HashMap<String, Object> m = new java.util.HashMap<>(base);
        m.put(k, v);
        return m;
    }

    @Test
    void prodProfile_withDefaultDbPassword_shouldFailFast() {
        Map<String, Object> props = with(
            with(baseProps(), "spring.datasource.password", "zorrodev"),
            "spring.rabbitmq.password", "Sec80Str0ng!RabbitPass");
        assertThatThrownBy(() -> {
            ConfigurableApplicationContext ctx = new SpringApplicationBuilder(TestMain.class)
                .web(WebApplicationType.SERVLET)
                .profiles("prod")
                .initializers(isolatedDb("sec80-db-fail"), creds("zorrodev", "Sec80Str0ng!RabbitPass"))
                .properties(props)
                .run();
            ctx.close();
        }).satisfies(ex -> {
            Throwable root = ex;
            while (root.getCause() != null) root = root.getCause();
            assertThat(root).isInstanceOf(IllegalStateException.class);
            assertThat(root.getMessage()).contains("spring.datasource.password");
        });
    }

    @Test
    void prodProfile_withDefaultRabbitPassword_shouldFailFast() {
        Map<String, Object> props = with(
            with(baseProps(), "spring.datasource.password", "Sec80Str0ng!DbPass"),
            "spring.rabbitmq.password", "zorrodev");
        assertThatThrownBy(() -> {
            ConfigurableApplicationContext ctx = new SpringApplicationBuilder(TestMain.class)
                .web(WebApplicationType.SERVLET)
                .profiles("prod")
                .initializers(isolatedDb("sec80-rabbit-fail"), creds("Sec80Str0ng!DbPass", "zorrodev"))
                .properties(props)
                .run();
            ctx.close();
        }).satisfies(ex -> {
            Throwable root = ex;
            while (root.getCause() != null) root = root.getCause();
            assertThat(root).isInstanceOf(IllegalStateException.class);
            assertThat(root.getMessage()).contains("spring.rabbitmq.password");
        });
    }

    @Test
    void prodProfile_withStrongPasswords_shouldStart() {
        Map<String, Object> props = with(
            with(baseProps(), "spring.datasource.password", "Sec80Str0ng!DbPass"),
            "spring.rabbitmq.password", "Sec80Str0ng!RabbitPass");
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(TestMain.class)
            .web(WebApplicationType.SERVLET)
            .profiles("prod")
            .initializers(isolatedDb("sec80-strong-ok"), creds("Sec80Str0ng!DbPass", "Sec80Str0ng!RabbitPass"))
            .properties(props)
            .run();
        try {
            assertThat(ctx.isRunning()).isTrue();
        } finally {
            ctx.close();
        }
    }

    @Test
    void devProfile_withDefaultPasswords_shouldStart() {
        Map<String, Object> props = with(
            with(baseProps(), "spring.datasource.password", "zorrodev"),
            "spring.rabbitmq.password", "zorrodev");
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(TestMain.class)
            .web(WebApplicationType.SERVLET)
            .profiles("dev")
            .initializers(isolatedDb("sec80-dev-ok"), creds("zorrodev", "zorrodev"))
            .properties(props)
            .run();
        try {
            assertThat(ctx.isRunning()).isTrue();
        } finally {
            ctx.close();
        }
    }
}
