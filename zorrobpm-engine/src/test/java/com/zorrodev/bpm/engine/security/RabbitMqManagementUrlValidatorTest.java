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

    /**
     * WO-QW-12 (ДОПОЛНЕНИЕ CTO, требование 1): путь В БАЗЕ — легальная часть
     * base-url, валидатор обязан её пропускать.
     *
     * <p>Это ровно та форма, которую задаёт compose после включения
     * {@code management.path_prefix = /rabbitmq} на брокере:
     * {@code http://rabbitmq:15672/rabbitmq}. Если бы fail-fast валидатор её отверг,
     * приложение не поднялось бы вовсе — то есть ошибка была бы громкой. Хуже другое:
     * без явного теста легко «ужесточить» предикат до «только хост:порт» (например,
     * запретив путь как «неожиданный»), и тогда префиксный compose ломает СТАРТ прод-приложения.
     *
     * <p>Проверено, что предлагаемое значение остаётся валидным и что соседние
     * не-URL по-прежнему отвергаются (иначе «разрешить путь»很容易 купить ценой
     * ослабления всей fail-fast проверки).
     */
    @Test
    void acceptsManagementPathPrefixInBase() {
        assertThat(RabbitMqManagementUrlValidator.isValidHttpUrl(
            "http://rabbitmq:15672/rabbitmq")).isTrue();
        assertThat(RabbitMqManagementUrlValidator.isValidHttpUrl(
            "http://127.0.0.1:15673/rabbitmq")).isTrue();
        // Соседи не должны потерять строгость вместе с расширением.
        assertThat(RabbitMqManagementUrlValidator.isValidHttpUrl("/rabbitmq")).isFalse();
        assertThat(RabbitMqManagementUrlValidator.isValidHttpUrl("rabbitmq:15672/rabbitmq")).isFalse();
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
