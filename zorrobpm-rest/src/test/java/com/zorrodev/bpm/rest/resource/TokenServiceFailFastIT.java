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
 * Proof-of-failure (V3) + Criterion #1:
 * Prod profile with default JWT secret should fail-fast at startup.
 * After the fix, TokenService throws IllegalStateException with readable message.
 *
 * <p>WO-URGENT-1 (NEW2-01): WO-SEC-80 added {@code DbRabbitPasswordValidator}
 * (a {@code BeanFactoryPostProcessor} — runs BEFORE bean instantiation, hence
 * before the {@code TokenService} constructor check). This test must set
 * non-default DB/Rabbit passwords — otherwise the password validator fails
 * first and masks the JWT gate this test pins.
 *
 * <p>WO-URGENT-1 (NEW2-01, criterion 2): the reverse test below pins the
 * order as an explicit contract — a default DB password fails BEFORE the
 * JWT check (structural: BFPP timing vs bean-construction timing, not bean
 * order). If someone reorders the validators, the reverse test names the
 * intended winner.
 */
class TokenServiceFailFastIT {

    /**
     * {@code SpringApplicationBuilder.properties(...)} registers DEFAULT
     * properties (lowest precedence) — they do NOT override
     * {@code src/test/resources/application.properties}, which pins
     * {@code spring.rabbitmq.password=zorrodev} for the suite. Credentials
     * therefore go through an initializer with {@code addFirst} (same
     * pattern as {@code DbRabbitPasswordFailFastIT}).
     */
    private static ApplicationContextInitializer<ConfigurableApplicationContext> isolatedDb(String dbName) {
        return ctx -> ctx.getEnvironment().getPropertySources().addFirst(
            new MapPropertySource("token-failfast-isolated-db", Map.of(
                "spring.datasource.url", "jdbc:h2:mem:" + dbName,
                "spring.datasource.driver-class-name", "org.h2.Driver")));
    }

    private static ApplicationContextInitializer<ConfigurableApplicationContext> creds(
            String dbPassword, String rabbitPassword) {
        return ctx -> ctx.getEnvironment().getPropertySources().addFirst(
            new MapPropertySource("token-failfast-creds", Map.of(
                "spring.datasource.password", dbPassword,
                "spring.rabbitmq.password", rabbitPassword)));
    }

    @Test
    void prodProfile_withDefaultSecret_shouldFailFast() {
        assertThatThrownBy(() -> {
            ConfigurableApplicationContext ctx = new SpringApplicationBuilder(TestMain.class)
                .web(WebApplicationType.SERVLET)
                .profiles("prod")
                .properties("server.port=0")
                .initializers(
                    isolatedDb("token-failfast-jwt"),
                    creds("TokFailfastStr0ng!DbPass", "TokFailfastStr0ng!RabbitPass"))
                .properties(
                    "spring.rabbitmq.host=localhost",
                    "spring.liquibase.enabled=false",
                    "zorrobpm.security.default-admin-password=TokFailfastStr0ng!AdminPass"
                )
                .run();
            ctx.close();
        }).satisfies(ex -> {
            Throwable root = ex;
            while (root.getCause() != null) root = root.getCause();
            assertThat(root).isInstanceOf(IllegalStateException.class);
            assertThat(root.getMessage()).containsIgnoringCase("jwt-secret");
        });
    }

    /**
     * WO-URGENT-1 criterion 2 (reverse order test): with BOTH a default DB
     * password AND a default JWT secret, startup must fail on the DB/Rabbit
     * gate — not on the JWT gate. The DB/Rabbit validator is a
     * {@code BeanFactoryPostProcessor} (runs before any bean is
     * instantiated); the JWT check lives in the {@code TokenService}
     * constructor (bean instantiation). BFPP-before-beans is a Spring
     * lifecycle guarantee, so this order is structural, not bean-order luck.
     */
    @Test
    void prodProfile_withDefaultDbPasswordAndDefaultSecret_failsOnDbGateFirst() {
        assertThatThrownBy(() -> {
            ConfigurableApplicationContext ctx = new SpringApplicationBuilder(TestMain.class)
                .web(WebApplicationType.SERVLET)
                .profiles("prod")
                .properties("server.port=0")
                .initializers(
                    isolatedDb("token-failfast-dborder"),
                    creds("zorrodev", "TokFailfastStr0ng!RabbitPass"))
                .properties(
                    "spring.rabbitmq.host=localhost",
                    "spring.liquibase.enabled=false",
                    "zorrobpm.security.default-admin-password=TokFailfastStr0ng!AdminPass"
                )
                .run();
            ctx.close();
        }).satisfies(ex -> {
            Throwable root = ex;
            while (root.getCause() != null) root = root.getCause();
            assertThat(root).isInstanceOf(IllegalStateException.class);
            assertThat(root.getMessage()).contains("spring.datasource.password");
            assertThat(root.getMessage()).doesNotContainIgnoringCase("jwt-secret");
        });
    }
}
