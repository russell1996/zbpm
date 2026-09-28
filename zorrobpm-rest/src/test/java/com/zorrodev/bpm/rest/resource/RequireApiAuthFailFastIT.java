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
 * WO-QW-9 (NEW4-14): {@code zorrobpm.security.require-api-auth=false} outside
 * dev/test must fail startup — it opens almost the entire API without
 * authentication ({@code JwtAuthFilter.isProtected}). Same class as
 * {@link DbRabbitPasswordFailFastIT} (full context, isolated H2 per boot).
 *
 * <p>POF link: without the validator the prod boot below starts fine and the
 * fail-fast test goes RED (no exception). Only the
 * {@code BeanFactoryPostProcessor} guard removes that RED.
 */
class RequireApiAuthFailFastIT {

    private static ApplicationContextInitializer<ConfigurableApplicationContext> isolatedDb(String dbName) {
        return ctx -> ctx.getEnvironment().getPropertySources().addFirst(
            new MapPropertySource("qw9-requireauth-isolated-db", Map.of(
                "spring.datasource.url", "jdbc:h2:mem:" + dbName,
                "spring.datasource.driver-class-name", "org.h2.Driver")));
    }

    private static ApplicationContextInitializer<ConfigurableApplicationContext> creds(
            String dbPassword, String rabbitPassword) {
        return ctx -> ctx.getEnvironment().getPropertySources().addFirst(
            new MapPropertySource("qw9-requireauth-creds", Map.of(
                "spring.datasource.password", dbPassword,
                "spring.rabbitmq.password", rabbitPassword,
                // Strong neighbours — ONLY the require-api-auth guard may fail.
                "zorrobpm.security.jwt-secret", "qw9-jwt-secret-not-default-0123456789",
                "zorrobpm.security.default-admin-password", "Qw9Str0ng!AdminPass",
                "zorrobpm.security.require-api-auth", "false")));
    }

    @Test
    void prodProfile_withRequireApiAuthFalse_shouldFailFast() {
        assertThatThrownBy(() -> {
            ConfigurableApplicationContext ctx = new SpringApplicationBuilder(TestMain.class)
                .web(WebApplicationType.SERVLET)
                .profiles("prod")
                .properties("server.port=0")
                .initializers(
                    isolatedDb("qw9-requireauth-fail"),
                    creds("Qw9Str0ng!DbPass", "Qw9Str0ng!RabbitPass"))
                .properties(
                    "spring.rabbitmq.host=localhost",
                    "spring.liquibase.enabled=false"
                )
                .run();
            ctx.close();
        }).satisfies(ex -> {
            Throwable root = ex;
            while (root.getCause() != null) root = root.getCause();
            assertThat(root).isInstanceOf(IllegalStateException.class);
            assertThat(root.getMessage()).contains("require-api-auth");
        });
    }

    @Test
    void prodProfile_withRequireApiAuthTrue_shouldStart() {
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(TestMain.class)
            .web(WebApplicationType.SERVLET)
            .profiles("prod")
            .properties("server.port=0")
            .initializers(
                isolatedDb("qw9-requireauth-ok"),
                ctx2 -> ctx2.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("qw9-requireauth-true", Map.of(
                        "spring.datasource.password", "Qw9Str0ng!DbPass",
                        "spring.rabbitmq.password", "Qw9Str0ng!RabbitPass",
                        "zorrobpm.security.jwt-secret", "qw9-jwt-secret-not-default-0123456789",
                        "zorrobpm.security.default-admin-password", "Qw9Str0ng!AdminPass",
                        "zorrobpm.security.require-api-auth", "true"))))
            .properties(
                "spring.rabbitmq.host=localhost",
                "spring.liquibase.enabled=false"
            )
            .run();
        try {
            assertThat(ctx.isRunning()).isTrue();
        } finally {
            ctx.close();
        }
    }

    @Test
    void devProfile_withRequireApiAuthFalse_shouldStart() {
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(TestMain.class)
            .web(WebApplicationType.SERVLET)
            .profiles("dev")
            .properties("server.port=0")
            .initializers(
                isolatedDb("qw9-requireauth-dev"),
                creds("zorrodev", "zorrodev"))
            .properties(
                "spring.rabbitmq.host=localhost",
                "spring.liquibase.enabled=false"
            )
            .run();
        try {
            assertThat(ctx.isRunning()).isTrue();
        } finally {
            ctx.close();
        }
    }
}
