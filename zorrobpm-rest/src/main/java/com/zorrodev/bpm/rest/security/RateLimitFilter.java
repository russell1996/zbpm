package com.zorrodev.bpm.rest.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rate-limits POST /auth/login per client IP.
 * Disabled by default ({@code zorrobpm.security.rate-limit.enabled=false}).
 * Enable per-profile or per-test via {@code @TestPropertySource}.
 */
@Slf4j
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final ConcurrentHashMap<String, RateBucket> buckets = new ConcurrentHashMap<>();

    @Value("${zorrobpm.security.rate-limit.enabled:false}")
    private boolean enabled;

    @Value("${zorrobpm.security.rate-limit.capacity:5}")
    private int capacity;

    @Value("${zorrobpm.security.rate-limit.window-seconds:60}")
    private int windowSeconds;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        if (!enabled || !"POST".equalsIgnoreCase(request.getMethod()) || !"/auth/login".equals(request.getRequestURI())) {
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
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
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
