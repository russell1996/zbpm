package com.zorrodev.bpm.rest.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Rate-limits POST /auth/login per client IP.
 *
 * WO-A-04: replaced unbounded ConcurrentHashMap with Caffeine cache
 * (maximumSize=100K, expireAfterAccess=10min) to prevent heap growth.
 * Rate limits and windows unchanged.
 *
 * Runs with {@code HIGHEST_PRECEDENCE + 1} to capture the real TCP remote IP
 * BEFORE Spring's {@code ForwardedHeaderFilter} rewrites it from X-Forwarded-For.
 * This prevents attackers from spoofing XFF to create new rate-limit buckets.
 */
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter implements Ordered {

    // WO-A-04: Caffeine cache — bounded, auto-evicts inactive IPs
    private final Cache<String, RateBucket> buckets = Caffeine.newBuilder()
        .maximumSize(100_000)
        .expireAfterAccess(10, TimeUnit.MINUTES)
        .build();
    private final Cache<String, RateBucket> dataEndpointBuckets = Caffeine.newBuilder()
        .maximumSize(100_000)
        .expireAfterAccess(10, TimeUnit.MINUTES)
        .build();

    private boolean enabled;

    private int capacity;

    private int windowSeconds;

    private int dataCapacity;

    private int dataWindowSeconds;

    void setRateLimitEnabled(boolean enabled) { this.enabled = enabled; }
    void setCapacity(int capacity) { this.capacity = capacity; }
    void setWindowSeconds(int windowSeconds) { this.windowSeconds = windowSeconds; }
    void setDataCapacity(int dataCapacity) { this.dataCapacity = dataCapacity; }
    void setDataWindowSeconds(int dataWindowSeconds) { this.dataWindowSeconds = dataWindowSeconds; }

    @Override
    public int getOrder() {
        // Run before ForwardedHeaderFilter (HIGHEST_PRECEDENCE + 5)
        // to capture real TCP remote IP before XFF rewriting
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }

        String path = PathNormalizer.normalize(request.getRequestURI());
        String method = request.getMethod();
        String clientIp = getClientIp(request);

        // Login endpoint: strict limit
        if ("POST".equalsIgnoreCase(method) && "/auth/login".equals(path)) {
            String key = "login:" + clientIp;
            RateBucket bucket = buckets.get(key, k -> new RateBucket(capacity, windowSeconds));
            long retryAfter = bucket.tryConsume();
            if (retryAfter > 0) {
                send429(response, retryAfter);
                return;
            }
        }

        // Data endpoints: generous limit (prevents brute-force enumeration, DoS via heavy queries)
        if (isDataEndpoint(method, path)) {
            String key = "data:" + clientIp;
            RateBucket bucket = dataEndpointBuckets.get(key, k -> new RateBucket(dataCapacity, dataWindowSeconds));
            long retryAfter = bucket.tryConsume();
            if (retryAfter > 0) {
                send429(response, retryAfter);
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private boolean isDataEndpoint(String method, String path) {
        if ("GET".equalsIgnoreCase(method) || "POST".equalsIgnoreCase(method)) {
            return path.startsWith("/events") || path.startsWith("/variables")
                || path.startsWith("/process-instances") || path.startsWith("/user-tasks")
                || path.startsWith("/service-tasks") || path.startsWith("/incidents");
        }
        return false;
    }

    private void send429(HttpServletResponse response, long retryAfter) throws IOException {
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", String.valueOf(retryAfter));
        response.getWriter().write(
            "{\"code\":\"RATE_LIMITED\",\"message\":\"Too many attempts, retry after " + retryAfter + "s\"}");
    }

    private String getClientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    /** Clears all buckets — for tests only. */
    public void reset() {
        buckets.invalidateAll();
        dataEndpointBuckets.invalidateAll();
    }

    private static class RateBucket {
        private final int capacity;
        private final long windowMillis;
        private long windowStart;
        private int tokens;

        RateBucket(int capacity, int windowSeconds) {
            this.capacity = capacity;
            this.windowMillis = windowSeconds * 1000L;
            this.tokens = capacity;
            this.windowStart = System.currentTimeMillis();
        }

        /** @return 0 if allowed, else seconds to wait */
        synchronized long tryConsume() {
            long now = System.currentTimeMillis();
            long elapsed = now - windowStart;
            if (elapsed >= windowMillis) {
                windowStart = now;
                tokens = capacity;
            }
            if (tokens > 0) {
                tokens--;
                return 0;
            }
            long waitMillis = windowMillis - (now - windowStart);
            return Math.max(1, waitMillis / 1000);
        }
    }
}
