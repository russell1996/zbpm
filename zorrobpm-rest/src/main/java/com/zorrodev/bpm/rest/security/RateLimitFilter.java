package com.zorrodev.bpm.rest.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Rate-limits POST /auth/login per client IP (cheap, no body read)
 * and per account (requires body read — guarded by IP check first).
 * Also rate-limits data endpoints per client IP.
 *
 * WO-SEC-44/45 HOLD fix: per-IP check runs BEFORE any body buffering.
 * Body is only read (with a 16 KB cap) if IP bucket allows.
 * This prevents memory-exhaustion DoS where attacker floods /auth/login
 * with oversized bodies that would all be buffered into heap.
 *
 * Runs with {@code HIGHEST_PRECEDENCE + 1} to capture the real TCP remote IP
 * BEFORE Spring's {@code ForwardedHeaderFilter} rewrites it from X-Forwarded-For.
 */
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter implements Ordered {

    /** Maximum bytes read from login request body (16 KB — login JSON is ~100 bytes). */
    private static final int MAX_LOGIN_BODY_BYTES = 16_384;

    private final Cache<String, RateBucket> ipBuckets = Caffeine.newBuilder()
        .maximumSize(100_000)
        .expireAfterAccess(10, TimeUnit.MINUTES)
        .build();

    private final Cache<String, RateBucket> accountBuckets = Caffeine.newBuilder()
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
    private int accountCapacity;
    private int accountWindowSeconds;
    private int dataCapacity;
    private int dataWindowSeconds;

    void setRateLimitEnabled(boolean enabled) { this.enabled = enabled; }
    void setCapacity(int capacity) { this.capacity = capacity; }
    void setWindowSeconds(int windowSeconds) { this.windowSeconds = windowSeconds; }
    void setAccountCapacity(int accountCapacity) { this.accountCapacity = accountCapacity; }
    void setAccountWindowSeconds(int accountWindowSeconds) { this.accountWindowSeconds = accountWindowSeconds; }
    void setDataCapacity(int dataCapacity) { this.dataCapacity = dataCapacity; }
    void setDataWindowSeconds(int dataWindowSeconds) { this.dataWindowSeconds = dataWindowSeconds; }

    @Override
    public int getOrder() {
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

        // STEP 1: Per-IP check (CHEAP — no body read, no memory allocation)
        if ("POST".equalsIgnoreCase(method) && "/auth/login".equals(path)) {
            String ipKey = "login:ip:" + clientIp;
            RateBucket ipBucket = ipBuckets.get(ipKey, k -> new RateBucket(capacity, windowSeconds));
            long ipRetryAfter = ipBucket.tryConsume();
            if (ipRetryAfter > 0) {
                // IP exhausted — 429 returned IMMEDIATELY, body never read
                send429(response, ipRetryAfter);
                return;
            }

            // STEP 2: Per-account check (body read — ONLY after IP check passes)
            if (accountCapacity > 0) {
                CachingRequestWrapper wrappedRequest = new CachingRequestWrapper(request);
                String username = extractUsernameFromBody(wrappedRequest);
                if (username != null) {
                    String acctKey = "login:acct:" + username.toLowerCase();
                    RateBucket acctBucket = accountBuckets.get(acctKey,
                        k -> new RateBucket(accountCapacity, accountWindowSeconds));
                    long acctRetryAfter = acctBucket.tryConsume();
                    if (acctRetryAfter > 0) {
                        send429(response, acctRetryAfter);
                        return;
                    }
                }
                chain.doFilter(wrappedRequest, response);
                return;
            }
        }

        // Data endpoints: generous limit
        if (isDataEndpoint(method, path) && dataCapacity > 0) {
            String dataKey = "data:" + clientIp;
            RateBucket bucket = dataEndpointBuckets.get(dataKey,
                k -> new RateBucket(dataCapacity, dataWindowSeconds));
            long retryAfter = bucket.tryConsume();
            if (retryAfter > 0) {
                send429(response, retryAfter);
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private String extractUsernameFromBody(HttpServletRequest request) {
        try {
            ServletInputStream is = request.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[256];
            int totalRead = 0;
            int n;
            while ((n = is.read(tmp)) != -1) {
                totalRead += n;
                if (totalRead > MAX_LOGIN_BODY_BYTES) {
                    log.warn("Login body exceeds {} bytes — rejecting", MAX_LOGIN_BODY_BYTES);
                    return null; // body too large — skip per-account check, IP limit already applied
                }
                buf.write(tmp, 0, n);
            }
            String body = buf.toString(StandardCharsets.UTF_8);
            // Extract "username":"..." from JSON (minimal parser — avoids Jackson dependency in filter)
            int idx = body.indexOf("\"username\"");
            if (idx < 0) return null;
            int colon = body.indexOf(':', idx + 10);
            if (colon < 0) return null;
            int startQuote = body.indexOf('"', colon + 1);
            if (startQuote < 0) return null;
            int endQuote = body.indexOf('"', startQuote + 1);
            if (endQuote < 0) return null;
            return body.substring(startQuote + 1, endQuote).trim();
        } catch (IOException e) {
            log.debug("Failed to read login body for per-account rate-limit: {}", e.getMessage());
            return null;
        }
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
        ipBuckets.invalidateAll();
        accountBuckets.invalidateAll();
        dataEndpointBuckets.invalidateAll();
    }

    /**
     * Wraps request to buffer the body for re-reading.
     * Body is capped at {@link #MAX_LOGIN_BODY_BYTES} to prevent memory exhaustion.
     */
    private static class CachingRequestWrapper extends HttpServletRequestWrapper {
        private final byte[] cachedBody;
        private final int bodyLength;

        CachingRequestWrapper(HttpServletRequest request) throws IOException {
            super(request);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[1024];
            int totalRead = 0;
            int n;
            ServletInputStream is = request.getInputStream();
            while ((n = is.read(tmp)) != -1) {
                totalRead += n;
                if (totalRead > MAX_LOGIN_BODY_BYTES) {
                    // Stop reading — body too large
                    break;
                }
                buf.write(tmp, 0, n);
            }
            this.cachedBody = buf.toByteArray();
            this.bodyLength = totalRead;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new ServletInputStream() {
                private int pos = 0;
                @Override public boolean isFinished() { return pos >= cachedBody.length; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {}
                @Override public int read() { return pos < cachedBody.length ? cachedBody[pos++] & 0xFF : -1; }
            };
        }

        @Override
        public int getContentLength() { return bodyLength; }

        @Override
        public long getContentLengthLong() { return bodyLength; }
    }

    static class RateBucket {
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
