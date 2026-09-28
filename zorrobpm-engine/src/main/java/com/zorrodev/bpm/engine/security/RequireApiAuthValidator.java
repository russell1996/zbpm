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
 * WO-QW-9 (NEW4-14): reject startup when {@code zorrobpm.security.require-api-auth}
 * is {@code false} outside {@code dev}/{@code test}.
 *
 * <p>Mirrors {@link DbRabbitPasswordValidator} (same SAFE_PROFILES, same
 * {@code BeanFactoryPostProcessor}-before-beans timing, same FATAL style):
 * with the flag off {@code JwtAuthFilter.isProtected} lets almost every API
 * path through without authentication. The default is {@code true} and no
 * shipped config overrides it — so this guards the "forgotten debug flag left
 * on in production" case, not a live setting. Runs BEFORE bean instantiation,
 * so no unauthenticated request window ever opens.
 */
@Slf4j
@Component
public class RequireApiAuthValidator implements BeanFactoryPostProcessor {

    private static final Set<String> SAFE_PROFILES = Set.of("dev", "test");

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        Environment environment = beanFactory.getBean(Environment.class);

        boolean safeProfile = java.util.Arrays.stream(environment.getActiveProfiles())
            .anyMatch(SAFE_PROFILES::contains);
        if (safeProfile) return;

        Boolean requireApiAuth = Binder.get(environment)
            .bind("zorrobpm.security.require-api-auth", Bindable.of(Boolean.class))
            .orElse(true);
        if (!requireApiAuth) {
            throw new IllegalStateException(
                "FATAL: zorrobpm.security.require-api-auth=false outside dev/test profiles. "
                + "This opens almost the entire API without authentication. "
                + "Remove the override for production.");
        }

        log.info("API auth requirement is enabled");
    }
}
