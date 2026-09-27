package com.zorrodev.bpm.engine.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-INT-9 (G-C.3, P-41): матрица предиката fail-fast валидатора
 * management base-url. Полное поведение (контекст падает до бинов) — в
 * {@code RabbitMqMgmtUrlFailFastIT} (rest, живой Spring-контекст).
 */
class RabbitMqManagementUrlValidatorTest {

    @Test
    void acceptsHttpAndHttps() {
        assertThat(RabbitMqManagementUrlValidator.isValidHttpUrl("http://rabbitmq:15672")).isTrue();
        assertThat(RabbitMqManagementUrlValidator.isValidHttpUrl("http://localhost:15672")).isTrue();
        assertThat(RabbitMqManagementUrlValidator.isValidHttpUrl("https://mgmt.internal:15671/api")).isTrue();
    }

    @Test
    void rejectsNonHttpAndMalformed() {
        assertThat(RabbitMqManagementUrlValidator.isValidHttpUrl("ftp://h:21")).isFalse();
        assertThat(RabbitMqManagementUrlValidator.isValidHttpUrl("rabbitmq:15672")).isFalse();
        assertThat(RabbitMqManagementUrlValidator.isValidHttpUrl("http://")).isFalse();
        assertThat(RabbitMqManagementUrlValidator.isValidHttpUrl("")).isFalse();
        assertThat(RabbitMqManagementUrlValidator.isValidHttpUrl(null)).isFalse();
    }
}
