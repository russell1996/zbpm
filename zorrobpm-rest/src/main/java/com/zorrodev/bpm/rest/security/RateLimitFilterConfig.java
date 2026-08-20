package com.zorrodev.bpm.rest.security;

import com.zorrodev.bpm.engine.repository.ApiKeyRepository;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * WO-SEC-44/45: Registers RateLimitFilter covering auth + data endpoints.
 *
 * Auth endpoints: /auth/login (per-IP + per-account), /auth/refresh (per-user).
 * Data endpoints: /events, /variables, /process-instances, /user-tasks,
 *                 /service-tasks, /incidents (per-IP generous limit).
 */
@Configuration
public class RateLimitFilterConfig {

    @Value("${zorrobpm.security.rate-limit.enabled:true}")
    private boolean enabled;

    @Value("${zorrobpm.security.rate-limit.capacity:5}")
    private int capacity;

    @Value("${zorrobpm.security.rate-limit.window-seconds:60}")
    private int windowSeconds;

    @Value("${zorrobpm.security.rate-limit.data-capacity:120}")
    private int dataCapacity;

    @Value("${zorrobpm.security.rate-limit.data-window-seconds:60}")
    private int dataWindowSeconds;

    @Value("${zorrobpm.security.rate-limit.account-capacity:5}")
    private int accountCapacity;

    @Value("${zorrobpm.security.rate-limit.refresh-capacity:30}")
    private int refreshCapacity;

    @Value("${zorrobpm.security.rate-limit.refresh-window-seconds:60}")
    private int refreshWindowSeconds;

    @Value("${zorrobpm.security.rate-limit.trusted-proxies:}")
    private String trustedProxiesRaw;

    @Bean
    public RateLimitFilter rateLimitFilter(ApiKeyRepository apiKeyRepository) {
        RateLimitFilter filter = new RateLimitFilter();
        filter.setRateLimitEnabled(enabled);
        filter.setCapacity(capacity);
        filter.setWindowSeconds(windowSeconds);
        filter.setDataCapacity(dataCapacity);
        filter.setDataWindowSeconds(dataWindowSeconds);
        filter.setAccountCapacity(accountCapacity);
        filter.setRefreshCapacity(refreshCapacity);
        filter.setRefreshWindowSeconds(refreshWindowSeconds);
        // WO-INT-4 criterion 10: per-key data quota needs key identity.
        filter.setApiKeyRepository(apiKeyRepository);

        Set<String> proxies = parseTrustedProxies(trustedProxiesRaw);
        filter.setTrustedProxies(proxies);
        if (!proxies.isEmpty()) {
            LoggerFactory.getLogger(RateLimitFilterConfig.class)
                .info("WO-SEC-44: trusted proxy IPs configured: {}", proxies);
        }

        return filter;
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        // WO-SEC-44: auth endpoints
        // WO-SEC-45: data endpoints (isDataEndpoint guard inside filter handles method+path matching)
        registration.addUrlPatterns(
            "/auth/login",
            "/auth/refresh",
            "/events/*",
            "/variables/*",
            "/process-instances/*",
            "/user-tasks/*",
            "/service-tasks/*",
            "/incidents/*"
        );
        registration.setName("rateLimitFilter");
        return registration;
    }

    private Set<String> parseTrustedProxies(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    }
}
