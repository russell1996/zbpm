package com.zorrodev.bpm.rest.resource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proof-of-failure (V3) + Criterion #1:
 * Prod profile with default JWT secret should fail-fast at startup.
 * After the fix, TokenService throws IllegalStateException with readable message.
 */
class TokenServiceFailFastIT {

    @Test
    void prodProfile_withDefaultSecret_shouldFailFast() {
        assertThatThrownBy(() -> {
            ConfigurableApplicationContext ctx = new SpringApplicationBuilder(TestMain.class)
                .web(WebApplicationType.SERVLET)
                .profiles("prod")
                .properties(
                    "spring.datasource.url=jdbc:h2:mem:prodtest",
                    "spring.datasource.driver-class-name=org.h2.Driver",
                    "spring.rabbitmq.host=localhost",
                    "spring.liquibase.enabled=false",
                    "zorrobpm.security.default-admin-password=not-admin-but-long"
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
}
