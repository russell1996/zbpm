package com.zorrodev.bpm.rest.security;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * WO-REL-21: registers {@link IdempotencyFilter} right after the rate limiter —
 * before auth, controllers, everything else. Auth interplay is handled inside the
 * filter itself (401/403 are never cached), so no security-file ordering is touched
 * (G-C). Exact paths only (never {@code /dmn/{id}/evaluate}, never GET).
 */
@Configuration
public class IdempotencyFilterConfig {

    @Bean
    public FilterRegistrationBean<IdempotencyFilter> idempotencyFilterRegistration(IdempotencyFilter filter) {
        FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns(
            "/process-instances",
            "/deployments",
            "/dmn",
            "/forms",
            "/auth/register"
        );
        registration.setName("idempotencyFilter");
        return registration;
    }
}
