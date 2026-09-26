package com.zorrodev.bpm.rest.security;

import com.zorrodev.bpm.engine.repository.ApiKeyRepository;
import com.zorrodev.bpm.engine.service.PgRateLimiter;
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
 * WO-SEC-64 (S-16): the same data paths now cover PUT/PATCH/DELETE, not only
 * GET/POST (method check lives in the filter; registration is path-based).
 * WO-API-4 (Finding #2): the admin/aux paths the audit found unthrottled
 * (/deployments, /users, /dmn, /forms, /me/api-key, /variable-schemas,
 * /admin/..., member paths) join the same data bucket — so they must be
 * registered here too, otherwise the filter never executes for them
 * (registration is path-based, the in-filter guard alone is dead code
 * for unregistered paths).
 *
 * WO-SCALE-2: rate-limit state is stored in PostgreSQL via {@code PgRateLimiter}
 * (cluster-safe), not in per-instance Caffeine caches.
 */
@Configuration
public class RateLimitFilterConfig {

    @Value("${zorrobpm.security.rate-limit.enabled:true}")
    private boolean enabled;

    @Value("${zorrobpm.security.rate-limit.capacity:5}")
    private int capacity;

    @Value("${zorrobpm.security.rate-limit.window-seconds:60}")
    private int windowSeconds;

    // WO-DIFF-6: 120 -> 300/min — диагностический поллинг list-эндпоинтов
    // (Raxon S-018: ~150 запросов за 15с упирался в 121-й). 300/мин = 5 r/s
    // средний — всё ещё в 6 раз ниже nginx-гейта 30 r/s; чтения дешёвые,
    // login/account/refresh-бакеты не тронуты.
    @Value("${zorrobpm.security.rate-limit.data-capacity:300}")
    private int dataCapacity;

    @Value("${zorrobpm.security.rate-limit.data-window-seconds:60}")
    private int dataWindowSeconds;

    @Value("${zorrobpm.security.rate-limit.account-capacity:5}")
    private int accountCapacity;

    @Value("${zorrobpm.security.rate-limit.refresh-capacity:30}")
    private int refreshCapacity;

    @Value("${zorrobpm.security.rate-limit.refresh-window-seconds:60}")
    private int refreshWindowSeconds;

    // WO-QW-5 (NEW2-11): отдельный IP-бакет refresh (не capacity логина).
    // Дефолт 60/мин — см. поле в фильтре.
    @Value("${zorrobpm.security.rate-limit.refresh-ip-capacity:60}")
    private int refreshIpCapacity;

    @Value("${zorrobpm.security.rate-limit.trusted-proxies:}")
    private String trustedProxiesRaw;

    @Bean
    public RateLimitFilter rateLimitFilter(ApiKeyRepository apiKeyRepository,
                                            com.zorrodev.bpm.engine.security.TokenService tokenService,
                                            PgRateLimiter pgRateLimiter) {
        RateLimitFilter filter = new RateLimitFilter();
        filter.setRateLimitEnabled(enabled);
        filter.setCapacity(capacity);
        filter.setWindowSeconds(windowSeconds);
        filter.setDataCapacity(dataCapacity);
        filter.setDataWindowSeconds(dataWindowSeconds);
        filter.setAccountCapacity(accountCapacity);
        filter.setRefreshCapacity(refreshCapacity);
        filter.setRefreshWindowSeconds(refreshWindowSeconds);
        filter.setRefreshIpCapacity(refreshIpCapacity);
        // WO-INT-4 criterion 8: per-key data quota needs key identity.
        filter.setApiKeyRepository(apiKeyRepository);
        // WO-SEC-58 HOLD-fix: /me/password bucket keyed on the JWT user, not client IP.
        filter.setTokenService(tokenService);
        // WO-SCALE-2: cluster-safe PG-backed rate limiter.
        filter.setPgRateLimiter(pgRateLimiter);

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
        // WO-API-4: admin/aux endpoints join the data bucket — registration must name
        // every prefix the guard matches (bare path + /* children, mirroring the
        // IdempotencyFilterConfig precedent for /deployments); the catch-all
        // /processes/* + /me/* registrations cover the */members* member paths
        // (/processes/{key}/members*, /me/memberships) — the guard narrows inside.
        registration.addUrlPatterns(
            "/auth/login",
            "/auth/refresh",
            "/me/password",
            "/events/*",
            "/variables/*",
            "/process-instances/*",
            "/user-tasks/*",
            "/service-tasks/*",
            "/incidents/*",
            "/process-definitions/*",
            "/deployments",
            "/deployments/*",
            "/users",
            "/users/*",
            "/dmn",
            "/dmn/*",
            "/forms",
            "/forms/*",
            "/me/api-key",
            "/me/api-key/*",
            "/me/memberships",
            "/me/memberships/*",
            "/me/*",
            "/variable-schemas/*",
            "/processes/*",
            "/admin/*"
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
