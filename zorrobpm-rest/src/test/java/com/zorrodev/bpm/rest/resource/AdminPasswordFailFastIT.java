package com.zorrodev.bpm.rest.resource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-SEC-14 Criterion #1 — Full-context fail-fast (V11):
 * Prod profile with default admin password → ApplicationContext fails to start.
 * After the fix, AdminPasswordValidator throws IllegalStateException.
 *
 * Criterion #2 (dev/test starts normally) is verified by existing tests
 * (SecurityHardeningIntegrationTest, ForcePasswordChangeEnforcementIT, etc.)
 * which all use test profile and start successfully.
 */
class AdminPasswordFailFastIT {

    @Test
    void prodProfile_withDefaultPassword_shouldFailFast() {
        assertThatThrownBy(() -> {
            ConfigurableApplicationContext ctx = new SpringApplicationBuilder(TestMain.class)
                .web(WebApplicationType.SERVLET)
                .profiles("prod")
                .properties(
                    "spring.datasource.url=jdbc:h2:mem:admintest-fail",
                    "spring.datasource.driver-class-name=org.h2.Driver",
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
}
