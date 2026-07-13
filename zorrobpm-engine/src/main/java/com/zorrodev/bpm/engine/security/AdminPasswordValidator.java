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
 * WO-SEC-14: In prod, reject startup if ZORROBPM_DEFAULT_ADMIN_PASSWORD
 * is missing or equals the default "admin".
 *
 * Uses BeanFactoryPostProcessor to run BEFORE bean instantiation,
 * ensuring the check happens before any other bean can fail.
 */
@Slf4j
@Component
public class AdminPasswordValidator implements BeanFactoryPostProcessor {

    private static final String DEFAULT_ADMIN_PASSWORD = "admin";
    private static final Set<String> PROD_REQUIRED_PROFILES = Set.of("prod");

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        Environment environment = beanFactory.getBean(Environment.class);

        boolean isProd = java.util.Arrays.stream(environment.getActiveProfiles())
            .anyMatch(PROD_REQUIRED_PROFILES::contains);
        if (!isProd) return;

        String password = Binder.get(environment)
            .bind("zorrobpm.security.default-admin-password", Bindable.of(String.class))
            .orElse(DEFAULT_ADMIN_PASSWORD);

        if (DEFAULT_ADMIN_PASSWORD.equals(password)) {
            throw new IllegalStateException(
                "FATAL: zorrobpm.security.default-admin-password must be set to a secure value "
                + "in production (default 'admin' is not allowed). "
                + "Set ZORROBPM_DEFAULT_ADMIN_PASSWORD or application-prod.yml.");
        }
        log.info("Admin password configured for production");
    }
}
