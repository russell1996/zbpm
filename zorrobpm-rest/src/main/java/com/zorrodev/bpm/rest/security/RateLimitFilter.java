package com.zorrodev.bpm.rest.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rate-limits POST /auth/login per client IP.
 *
 * Runs with {@code HIGHEST_PRECEDENCE + 1} to capture the real TCP remote IP
 * BEFORE Spring's {@code ForwardedHeaderFilter} rewrites it from X-Forwarded-For.
 * This prevents attackers from spoofing XFF to create new rate-limit buckets.
 */
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter implements Ordered {

    private final ConcurrentHashMap<String, RateBucket> buckets = new ConcurrentHashMap<>();

    private boolean enabled;

    private int capacity;

    private int windowSeconds;

    void setRateLimitEnabled(boolean enabled) { this.enabled = enabled; }
    void setCapacity(int capacity) { this.capacity = capacity; }
    void setWindowSeconds(int windowSeconds) { this.windowSeconds = windowSeconds; }

    @Override
    public int getOrder() {
        // Run before ForwardedHeaderFilter (HIGHEST_PRECEDENCE + 5)
        // to capture real TCP remote IP before XFF rewriting
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String path = PathNormalizer.normalize(request.getRequestURI());
        if (!enabled || !"POST".equalsIgnoreCase(request.getMethod()) || !"/auth/login".equals(path)) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = getClientIp(request);
        RateBucket bucket = buckets.computeIfAbsent(clientIp, k -> new RateBucket(capacity, windowSeconds));

        long retryAfter = bucket.tryConsume();
        if (retryAfter == 0) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            response.getWriter().write(
                "{\"code\":\"RATE_LIMITED\",\"message\":\"Too many attempts, retry after " + retryAfter + "s\"}");
        }
    }

    private String getClientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    /** Clears all buckets — for tests only. */
    public void reset() {
        buckets.clear();
    }

    private static class RateBucket {
        private final int capacity;
        private final long windowMillis;
        private final AtomicInteger tokens;
        private final AtomicLong windowStart;

        RateBucket(int capacity, int windowSeconds) {
            this.capacity = capacity;
            this.windowMillis = windowSeconds * 1000L;
            this.tokens = new AtomicInteger(capacity);
            this.windowStart = new AtomicLong(System.currentTimeMillis());
        }

        /** @return 0 if allowed, else seconds to wait */
        long tryConsume() {
            long now = System.currentTimeMillis();
            long elapsed = now - windowStart.get();
            if (elapsed >= windowMillis) {
                if (windowStart.compareAndSet(windowStart.get(), now)) {
                    tokens.set(capacity);
                }
            }
            int remaining = tokens.decrementAndGet();
            if (remaining >= 0) {
                return 0;
            }
            tokens.incrementAndGet();
            long waitMillis = windowMillis - (System.currentTimeMillis() - windowStart.get());
            return Math.max(1, waitMillis / 1000);
        }
    }
}
