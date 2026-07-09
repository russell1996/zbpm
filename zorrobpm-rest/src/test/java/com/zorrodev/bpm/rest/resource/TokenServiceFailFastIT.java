package com.zorrodev.bpm.rest.resource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proof-of-failure (V3) + Criterion #1:
 * Prod profile with default JWT secret should fail-fast at startup.
 * On current code this test PASSES (context starts) — it should FAIL.
 */
class TokenServiceFailFastIT {

    @Test
    void prodProfile_withDefaultSecret_shouldFailFast() {
        assertThatThrownBy(() -> {
            ConfigurableApplicationContext ctx = new SpringApplicationBuilder(TestMain.class)
                .web(WebApplicationType.SERVLET)
                .profiles("prod")
                .properties(
                    "spring.datasource.url=jdbc:h2:mem:failfast",
                    "spring.rabbitmq.host=localhost"
                )
                .run();
            ctx.close();
        }).hasCauseInstanceOf(IllegalStateException.class)
          .satisfies(ex -> {
              Throwable cause = ex.getCause();
              assertThat(cause.getMessage()).containsIgnoringCase("jwt-secret");
          });
    }
}
