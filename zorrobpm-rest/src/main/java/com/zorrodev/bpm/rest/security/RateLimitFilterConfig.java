package com.zorrodev.bpm.rest.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers RateLimitFilter as a bean with HIGHEST_PRECEDENCE + 1 order,
 * ensuring it runs BEFORE Spring's ForwardedHeaderFilter (HIGHEST_PRECEDENCE + 5).
 * This captures the real TCP remote IP before XFF rewriting.
 */
@Configuration
public class RateLimitFilterConfig {

    @Value("${zorrobpm.security.rate-limit.enabled:true}")
    private boolean enabled;

    @Value("${zorrobpm.security.rate-limit.capacity:5}")
    private int capacity;

    @Value("${zorrobpm.security.rate-limit.window-seconds:60}")
    private int windowSeconds;

    @Value("${zorrobpm.security.rate-limit.account-capacity:3}")
    private int accountCapacity;

    @Value("${zorrobpm.security.rate-limit.account-window-seconds:300}")
    private int accountWindowSeconds;

    @Value("${zorrobpm.security.rate-limit.data-capacity:120}")
    private int dataCapacity;

    @Value("${zorrobpm.security.rate-limit.data-window-seconds:60}")
    private int dataWindowSeconds;

    @Bean
    public RateLimitFilter rateLimitFilter() {
        RateLimitFilter filter = new RateLimitFilter();
        filter.setRateLimitEnabled(enabled);
        filter.setCapacity(capacity);
        filter.setWindowSeconds(windowSeconds);
        filter.setAccountCapacity(accountCapacity);
        filter.setAccountWindowSeconds(accountWindowSeconds);
        filter.setDataCapacity(dataCapacity);
        filter.setDataWindowSeconds(dataWindowSeconds);
        return filter;
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/auth/login");
        registration.setName("rateLimitFilter");
        return registration;
    }
}
