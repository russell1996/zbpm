package com.zorrodev.bpm.engine.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * WO-INT-9 (G-C.3, P-41): новая ручка {@code zorrobpm.rabbitmq.management.base-url}
 * проходит ту же fail-fast проверку, что соседние security-настройки
 * ({@link DbRabbitPasswordValidator}, {@code AdminPasswordValidator} — тот же
 * {@code BeanFactoryPostProcessor}-before-beans timing, тот же FATAL-стиль).
 *
 * <p>Основание строже соседей осознанно: у паролей есть «безопасный» dev/test
 * режим, а у base-url невалидное значение невалидно везде — провижининг слал
 * бы креды не туда (или никуда с fail-closed 503 на membership-мутациях).
 * Поэтому проверка безусловна (все профили): значение обязано быть
 * {@code http(s)://…}. Дефолт {@code http://localhost:15672} валиден —
 * существующие окружения стартуют без изменений.
 */
@Slf4j
@Component
public class RabbitMqManagementUrlValidator implements BeanFactoryPostProcessor {

    static final String PROPERTY = "zorrobpm.rabbitmq.management.base-url";
    static final String DEFAULT_URL = "http://localhost:15672";

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        Environment environment = beanFactory.getBean(Environment.class);

        String baseUrl = Binder.get(environment)
            .bind(PROPERTY, Bindable.of(String.class))
            .orElse(DEFAULT_URL);

        if (!isValidHttpUrl(baseUrl)) {
            throw new IllegalStateException(
                "FATAL: " + PROPERTY + " must be an http(s) URL (got '" + baseUrl + "'). "
                + "Set RABBITMQ_MGMT_BASE_URL (e.g. http://rabbitmq:15672 in compose) "
                + "or fix the property value.");
        }

        log.info("RabbitMQ management base-url is a valid http(s) URL");
    }

    /** Package-visible для юнит-теста (тот же приём, что JobQueueDeclarer). */
    static boolean isValidHttpUrl(String value) {
        if (value == null) return false;
        String trimmed = value.trim();
        if (!(trimmed.startsWith("http://") || trimmed.startsWith("https://"))) {
            return false;
        }
        try {
            java.net.URI uri = new java.net.URI(trimmed);
            return uri.getHost() != null;
        } catch (Exception e) {
            return false;
        }
    }
}
