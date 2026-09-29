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
 * WO-SEC-14 Criterion #1 — Full-context fail-fast (V11):
 * Prod profile with default admin password → ApplicationContext fails to start.
 * After the fix, AdminPasswordValidator throws IllegalStateException.
 *
 * WO-SEC-68: validation inverted — runs on EVERY profile except the explicit
 * safe list ({@code dev}, {@code test}):
 * - default profile (no explicit name) + weak password → startup fails (criterion 1)
 * - prod profile + weak password → still fails (criterion 2, regression of WO-SEC-14)
 * - dev profile + default password → starts (criterion 3, local dev not broken)
 *
 * Criterion #3 for the {@code test} profile is verified by every other IT
 * in this module (all use {@code @ActiveProfiles("test")} and start successfully).
 */
class AdminPasswordFailFastIT {

    /**
     * WO-SEC-68: {@code SpringApplicationBuilder.properties(...)} registers
     * DEFAULT properties (lowest precedence) — they do NOT override
     * {@code src/test/resources/application.properties}, which pins
     * {@code spring.datasource.url=jdbc:h2:mem:test} for the whole suite.
     * Without this initializer every boot below would share the suite-wide H2
     * database: the dev-profile boot runs Hibernate {@code create-drop} on it
     * and leaves it table-less on close, breaking subsequently reused cached
     * contexts of other ITs ({@code Table "UI_USERS" not found}). An
     * {@code ApplicationContextInitializer} with {@code addFirst} wins over
     * every property file, so each boot gets a truly private in-memory DB.
     */
    private static ApplicationContextInitializer<ConfigurableApplicationContext> isolatedDb(String dbName) {
        return ctx -> ctx.getEnvironment().getPropertySources().addFirst(
            new MapPropertySource("sec68-isolated-db", Map.of(
                "spring.datasource.url", "jdbc:h2:mem:" + dbName,
                "spring.datasource.driver-class-name", "org.h2.Driver")));
    }

    @Test
    void prodProfile_withDefaultPassword_shouldFailFast() {
        assertThatThrownBy(() -> {
            ConfigurableApplicationContext ctx = new SpringApplicationBuilder(TestMain.class)
                .web(WebApplicationType.SERVLET)
                // WO-QW-4 (NEW-15): random port — a fixed 8080 BindExceptions the
                // whole reactor on any host with the port taken.
                .properties("server.port=0")
                .profiles("prod")
                .initializers(isolatedDb("admintest-fail"))
                .properties(
                    "spring.rabbitmq.host=localhost",
                    "spring.liquibase.enabled=false",
                    "zorrobpm.security.jwt-secret=super-secret-prod-jwt-key-not-default",
                    "zorrobpm.security.default-admin-password=admin"
                )
                .run();
            ctx.close();
        }).satisfies(ex -> {
            Throwable root = ex;
            while (root.getCause() != null) root = root.getCause();
            assertThat(root).isInstanceOf(IllegalStateException.class);
            assertThat(root.getMessage()).contains("default-admin-password");
        });
    }

    /**
     * WO-SEC-68 criterion 1: NO explicit profile (the forgotten-flag deploy case)
     * with a weak password must fail startup with the same message as prod.
     * A strong jwt-secret is set so that ONLY the admin-password validator can
     * be the failure cause (otherwise TokenService fail-fast would mask it).
     */
    @Test
    void defaultProfile_withDefaultPassword_shouldFailFast() {
        assertThatThrownBy(() -> {
            ConfigurableApplicationContext ctx = new SpringApplicationBuilder(TestMain.class)
                .web(WebApplicationType.SERVLET)
                // WO-QW-4 (NEW-15): random port — a fixed 8080 BindExceptions the
                // whole reactor on any host with the port taken.
                .properties("server.port=0")
                .initializers(isolatedDb("admintest-default-profile"))
                .properties(
                    "spring.rabbitmq.host=localhost",
                    "spring.liquibase.enabled=false",
                    "zorrobpm.security.jwt-secret=default-profile-jwt-secret-not-default-0123",
                    "zorrobpm.security.default-admin-password=admin"
                )
                .run();
            ctx.close();
        }).satisfies(ex -> {
            Throwable root = ex;
            while (root.getCause() != null) root = root.getCause();
            assertThat(root).isInstanceOf(IllegalStateException.class);
            assertThat(root.getMessage()).contains("default-admin-password");
        });
    }

    /**
     * WO-SEC-68 criterion 3: {@code dev} profile with the default password must
     * still start (local development not broken by the inverted gate).
     */
    @Test
    void devProfile_withDefaultPassword_shouldStart() {
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(TestMain.class)
            .web(WebApplicationType.SERVLET)
            // WO-QW-4 (NEW-15): random port, см. выше.
            .properties("server.port=0")
            .profiles("dev")
            .initializers(isolatedDb("admintest-dev-profile"))
            .properties(
                "spring.rabbitmq.host=localhost",
                "spring.liquibase.enabled=false",
                "zorrobpm.security.default-admin-password=admin"
            )
            .run();
        try {
            assertThat(ctx.isRunning()).isTrue();
        } finally {
            ctx.close();
        }
    }
}
