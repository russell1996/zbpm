package com.zorrodev.bpm.engine.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * WO-SEC-80 (F29): reject startup when the DB/RabbitMQ passwords are still the
 * shipped defaults outside {@code dev}/{@code test}.
 *
 * <p>Mirrors {@link AdminPasswordValidator} (same SAFE_PROFILES, same
 * {@code BeanFactoryPostProcessor}-before-beans timing, same FATAL style):
 * {@code application-prod.yml} declares {@code ${DB_PASSWORD:zorrodev}} /
 * {@code ${RABBITMQ_PASSWORD:zorrodev}}, so a forgotten env override boots
 * prod on the public default silently. JWT-secret and admin-password already
 * fail fast on their defaults — these two were the remaining gap.
 *
 * <p>Runs BEFORE bean instantiation, so no connection is ever opened with a
 * default credential (the datasource/rabbit factories would otherwise happily
 * connect to a same-default broker).
 */
@Slf4j
@Component
public class DbRabbitPasswordValidator implements BeanFactoryPostProcessor {

    private static final Set<String> SAFE_PROFILES = Set.of("dev", "test");
    static final String DEFAULT_PASSWORD = "zorrodev";

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        Environment environment = beanFactory.getBean(Environment.class);

        boolean safeProfile = java.util.Arrays.stream(environment.getActiveProfiles())
            .anyMatch(SAFE_PROFILES::contains);
        if (safeProfile) return;

        String dbPassword = Binder.get(environment)
            .bind("spring.datasource.password", Bindable.of(String.class))
            .orElse(DEFAULT_PASSWORD);
        if (DEFAULT_PASSWORD.equals(dbPassword)) {
            throw new IllegalStateException(
                "FATAL: spring.datasource.password is the shipped default ('zorrodev'). "
                + "Set DB_PASSWORD (or DB_URL with credentials) for production.");
        }

        String rabbitPassword = Binder.get(environment)
            .bind("spring.rabbitmq.password", Bindable.of(String.class))
            .orElse(DEFAULT_PASSWORD);
        if (DEFAULT_PASSWORD.equals(rabbitPassword)) {
            throw new IllegalStateException(
                "FATAL: spring.rabbitmq.password is the shipped default ('zorrodev'). "
                + "Set RABBITMQ_PASSWORD for production.");
        }

        log.info("DB/RabbitMQ passwords are not defaults");
    }
}
