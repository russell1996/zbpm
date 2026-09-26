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
 * WO-SEC-27b: Warn/fail-fast if DB or RabbitMQ passwords are default/empty in prod.
 *
 * Behavior controlled by {@code zorrobpm.security.enforce-db-creds} (default=false):
 * - false: log WARN if default password detected, application starts (soft enforcement)
 * - true:  throw IllegalStateException, application fails to start (hard enforcement)
 *
 * Set enforce-db-creds=true AFTER credential rotation on the host.
 */
@Slf4j
@Component
public class CredentialsValidator implements BeanFactoryPostProcessor {

    private static final Set<String> PROD_PROFILES = Set.of("prod");
    private static final String DEFAULT_PASSWORD = "zorrodev";

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        Environment environment = beanFactory.getBean(Environment.class);

        boolean isProd = java.util.Arrays.stream(environment.getActiveProfiles())
            .anyMatch(PROD_PROFILES::contains);
        if (!isProd) return;

        boolean enforce = Boolean.parseBoolean(
            Binder.get(environment)
                .bind("zorrobpm.security.enforce-db-creds", Bindable.of(String.class))
                .orElse("false"));

        // Check DB credentials
        String dbPassword = Binder.get(environment)
            .bind("spring.datasource.password", Bindable.of(String.class))
            .orElse(DEFAULT_PASSWORD);
        checkCredential("DB_PASSWORD (spring.datasource.password)", dbPassword, enforce);

        // Check RabbitMQ credentials
        String rmqPassword = Binder.get(environment)
            .bind("spring.rabbitmq.password", Bindable.of(String.class))
            .orElse(DEFAULT_PASSWORD);
        checkCredential("RABBITMQ_PASSWORD (spring.rabbitmq.password)", rmqPassword, enforce);
    }

    private void checkCredential(String name, String password, boolean enforce) {
        if (password == null || password.isBlank() || DEFAULT_PASSWORD.equals(password)) {
            String message = String.format(
                "SECURITY WARNING: %s is using default/empty credentials in production. "
                + "Rotate credentials before enabling zorrobpm.security.enforce-db-creds=true. "
                + "See governance/runbooks/credential-rotation.md for procedure.", name);
            if (enforce) {
                throw new IllegalStateException("FATAL: " + message);
            } else {
                log.warn(message);
            }
        } else {
            log.info("{} configured for production (length={})", name, password.length());
        }
    }
}
