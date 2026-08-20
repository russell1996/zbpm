package com.zorrodev.bpm.rest.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zorrodev.bpm.engine.entity.ApiKeyEntity;
import com.zorrodev.bpm.engine.repository.ApiKeyRepository;
import com.zorrodev.bpm.engine.security.KeyHasher;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * WO-SEC-44/45: Rate-limits auth + data endpoints.
 *
 * <p>Login (POST /auth/login): two independent buckets — per-IP AND per-account.
 * Both must have capacity for the request to pass. This prevents:
 * <ul>
 *   <li>Self-DoS behind shared proxy (per-IP bucket shared by all users → one attacker blocks all)</li>
 *   <li>Brute-force per account (attacker spreads attempts across IPs)</li>
 * </ul>
 *
 * <p>Refresh (POST /auth/refresh): per-user bucket extracted from refresh token cookie.
 *
 * <p>Data endpoints: per-IP generous limit (WO-SEC-45).
 *
 * <p>Trusted proxy support: when {@code zorrobpm.security.rate-limit.trusted-proxies} is configured,
 * the filter extracts the real client IP from X-Forwarded-For (leftmost non-trusted IP).
 * Untrusted XFF headers are ignored (WO-SEC-12/13 anti-spoofing preserved).
 *
 * <p>HOLD-fix (body-buffering DoS): per-IP check runs BEFORE any body read.
 * The body is only buffered (with a 16 KB cap) after the IP bucket allows the request.
 * Requests with a declared body larger than the cap are rejected with 413 BEFORE reading.
 * This prevents memory-exhaustion on the public unauthenticated /auth/login endpoint.
 *
 * <p>Runs with {@code HIGHEST_PRECEDENCE + 1} — before Spring's {@code ForwardedHeaderFilter}
 * ({@code HIGHEST_PRECEDENCE + 5}) to capture IP before XFF rewriting.
 */
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter implements Ordered {

    /** Maximum bytes buffered from login request body (login JSON is ~100 bytes). */
    static final int MAX_LOGIN_BODY_BYTES = 16_384;

    private final Cache<String, RateBucket> ipBuckets = Caffeine.newBuilder()
        .maximumSize(100_000)
        .expireAfterAccess(10, TimeUnit.MINUTES)
        .build();
    private final Cache<String, RateBucket> accountBuckets = Caffeine.newBuilder()
        .maximumSize(100_000)
        .expireAfterAccess(10, TimeUnit.MINUTES)
        .build();
    private final Cache<String, RateBucket> refreshBuckets = Caffeine.newBuilder()
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
    /** WO-SEC-44: per-account login limit (separate from IP limit). Default same as capacity. */
    private int accountCapacity;
    /** WO-SEC-44: per-user refresh limit. Default: 30 per window. */
    private int refreshCapacity;
    /** WO-SEC-44: refresh window in seconds. Default: 60. */
    private int refreshWindowSeconds;
    /** WO-SEC-44: trusted proxy IPs/CIDRs. Empty = ignore XFF (current behavior). */
    private Set<String> trustedProxies = Set.of();

    /** WO-INT-4: resolves API-key identity for per-key data quotas. */
    private ApiKeyRepository apiKeyRepository;

    void setRateLimitEnabled(boolean enabled) { this.enabled = enabled; }
    void setCapacity(int capacity) { this.capacity = capacity; }
    void setWindowSeconds(int windowSeconds) { this.windowSeconds = windowSeconds; }
    void setDataCapacity(int dataCapacity) { this.dataCapacity = dataCapacity; }
    void setDataWindowSeconds(int dataWindowSeconds) { this.dataWindowSeconds = dataWindowSeconds; }
    void setAccountCapacity(int accountCapacity) { this.accountCapacity = accountCapacity; }
    void setRefreshCapacity(int refreshCapacity) { this.refreshCapacity = refreshCapacity; }
    void setRefreshWindowSeconds(int refreshWindowSeconds) { this.refreshWindowSeconds = refreshWindowSeconds; }
    void setTrustedProxies(Set<String> trustedProxies) { this.trustedProxies = trustedProxies; }

    void setApiKeyRepository(ApiKeyRepository apiKeyRepository) { this.apiKeyRepository = apiKeyRepository; }

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

        // Login endpoint: per-IP + per-account throttling
        if ("POST".equalsIgnoreCase(method) && "/auth/login".equals(path)) {
            // STEP 1: per-IP check — CHEAP, no body read, no memory allocation.
            // This MUST run before any body buffering (HOLD-fix: memory-exhaustion DoS).
            String ipKey = "login:ip:" + clientIp;
            RateBucket ipBucket = ipBuckets.get(ipKey, k -> new RateBucket(capacity, windowSeconds));
            long ipRetryAfter = ipBucket.tryConsume();
            if (ipRetryAfter > 0) {
                send429(response, ipRetryAfter);
                return;
            }

            // STEP 2: reject oversized declared bodies BEFORE reading (413, no buffering).
            // Defense in depth: an attacker declaring a 1 GB body is rejected by
            // Content-Length alone, without a single byte allocated.
            long declaredLength = request.getContentLengthLong();
            if (declaredLength > MAX_LOGIN_BODY_BYTES) {
                log.warn("Login body declared {} bytes, cap is {} — rejecting 413", declaredLength, MAX_LOGIN_BODY_BYTES);
                send413(response);
                return;
            }

            // STEP 3: buffer body (capped) for per-account extraction.
            CachingRequestWrapper wrappedRequest = new CachingRequestWrapper(request);

            // STEP 3b: chunked/no-Content-Length body that exceeded the cap in-flight → 413.
            // The wrapper only buffered READ_CAP bytes; without this rejection the stream
            // would carry a truncated body (length/stream desync), so we refuse instead.
            if (wrappedRequest.isOversized()) {
                log.warn("Login body exceeds {} bytes (chunked) — rejecting 413", MAX_LOGIN_BODY_BYTES);
                send413(response);
                return;
            }

            // STEP 4: per-account bucket (disabled when accountCapacity == 0).
            String username = extractUsername(wrappedRequest);
            if (accountCapacity > 0 && username != null && !username.isBlank()) {
                String acctKey = "login:account:" + username.toLowerCase();
                RateBucket acctBucket = accountBuckets.get(acctKey, k -> new RateBucket(accountCapacity, windowSeconds));
                long acctRetryAfter = acctBucket.tryConsume();
                if (acctRetryAfter > 0) {
                    // Rollback IP bucket token — request rejected by account limit, not IP limit
                    ipBucket.rollback();
                    send429(response, acctRetryAfter);
                    return;
                }
            }

            chain.doFilter(wrappedRequest, response);
            return;
        }

        // Refresh endpoint: per-user throttling
        if ("POST".equalsIgnoreCase(method) && "/auth/refresh".equals(path)) {
            String userId = extractUserIdFromRefreshCookie(request);
            if (userId != null) {
                String refreshKey = "refresh:" + userId;
                RateBucket refreshBucket = refreshBuckets.get(refreshKey, k -> new RateBucket(refreshCapacity, refreshWindowSeconds));
                long retryAfter = refreshBucket.tryConsume();
                if (retryAfter > 0) {
                    send429(response, retryAfter);
                    return;
                }
            }
            chain.doFilter(request, response);
            return;
        }

        // Data endpoints: generous limit
        if (isDataEndpoint(method, path)) {
            // WO-INT-4 criterion 10: quota is counted per API KEY, not per IP — an
            // integration BFF calling from one address must not be throttled by another
            // BFF on the same address (and must not exhaust the shared per-IP quota).
            String key = resolveDataBucketKey(request, clientIp);
            RateBucket bucket = dataEndpointBuckets.get(key, k -> new RateBucket(dataCapacity, dataWindowSeconds));
            long retryAfter = bucket.tryConsume();
            if (retryAfter > 0) {
                send429(response, retryAfter);
                return;
            }
        }

        chain.doFilter(request, response);
    }

    /**
     * WO-INT-4: data-endpoint rate-limit key. Requests carrying a VALID service key are
     * keyed by the key's id (one quota per key); everything else falls back to the IP.
     * An invalid/unknown key still falls back to the IP bucket — the auth filter rejects
     * it afterwards with 401, so no quota bypass is possible.
     */
    private String resolveDataBucketKey(HttpServletRequest request, String clientIp) {
        if (apiKeyRepository == null) return "data:" + clientIp;
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer zbpm_sk_")) {
            return "data:" + clientIp;
        }
        String token = header.substring(7);
        ApiKeyEntity key = apiKeyRepository.findByKeyHash(KeyHasher.sha256(token)).orElse(null);
        if (key == null || key.getRevokedAt() != null) return "data:" + clientIp;
        if (key.getExpiresAt() != null && key.getExpiresAt().isBefore(Instant.now())) return "data:" + clientIp;
        return "data:key:" + key.getId();
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

    private void send413(HttpServletResponse response) throws IOException {
        response.setStatus(413);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
            "{\"code\":\"PAYLOAD_TOO_LARGE\",\"message\":\"Request body exceeds " + MAX_LOGIN_BODY_BYTES + " bytes\"}");
    }

    /**
     * WO-SEC-44: extract client IP, respecting trusted proxy configuration.
     * If remoteAddr is a trusted proxy, extract real IP from X-Forwarded-For (leftmost).
     * Otherwise, use remoteAddr directly (XFF ignored — anti-spoofing).
     */
    private String getClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (trustedProxies.isEmpty() || !isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }
        // Trusted proxy: extract real client IP from X-Forwarded-For
        String xff = request.getHeader("X-Forwarded-For");
        if (xff == null || xff.isBlank()) {
            return remoteAddr;
        }
        // XFF format: "client, proxy1, proxy2" — take leftmost (original client)
        String[] ips = xff.split(",");
        return ips[0].trim();
    }

    /**
     * Check if an IP is in the trusted proxy list. Supports exact match and CIDR notation.
     */
    private boolean isTrustedProxy(String ip) {
        for (String trusted : trustedProxies) {
            if (trusted.contains("/")) {
                if (matchesCidr(ip, trusted)) return true;
            } else {
                if (trusted.equals(ip)) return true;
            }
        }
        return false;
    }

    /**
     * Simple CIDR match for IPv4. Supports /8, /16, /24, /32 masks.
     */
    static boolean matchesCidr(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            String network = parts[0];
            int prefixLen = Integer.parseInt(parts[1]);

            long ipNum = ipToLong(ip);
            long networkNum = ipToLong(network);
            long mask = prefixLen == 0 ? 0L : (~0L) << (32 - prefixLen);

            return (ipNum & mask) == (networkNum & mask);
        } catch (Exception e) {
            return false;
        }
    }

    private static long ipToLong(String ip) {
        String[] parts = ip.split("\\.");
        long result = 0;
        for (String part : parts) {
            result = result * 256 + Long.parseLong(part.trim());
        }
        return result;
    }

    /**
     * WO-SEC-44: extract username from login request body.
     * Uses CachingRequestWrapper so the body can be read by the controller after this filter.
     */
    private String extractUsername(CachingRequestWrapper request) {
        try {
            byte[] body = request.getBodyBytes();
            if (body == null || body.length == 0) return null;
            String json = new String(body, StandardCharsets.UTF_8);
            // Minimal JSON parsing — avoid pulling in ObjectMapper for a simple field
            // Format: {"username":"...","password":"..."}
            int idx = json.indexOf("\"username\"");
            if (idx < 0) return null;
            int colon = json.indexOf(':', idx + 10);
            if (colon < 0) return null;
            int openQuote = json.indexOf('"', colon + 1);
            if (openQuote < 0) return null;
            int closeQuote = json.indexOf('"', openQuote + 1);
            if (closeQuote < 0) return null;
            return json.substring(openQuote + 1, closeQuote);
        } catch (Exception e) {
            log.debug("Failed to extract username from login body: {}", e.getMessage());
            return null;
        }
    }

    /**
     * WO-SEC-44: extract userId from refresh_token cookie for rate-limiting.
     * The cookie contains the raw refresh token — we can't verify it here (no TokenService),
     * so we use the token value itself as the rate-limit key. Different tokens = different buckets.
     */
    private String extractUserIdFromRefreshCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if ("refresh_token".equals(cookie.getName())) {
                String value = cookie.getValue();
                if (value != null && !value.isBlank()) {
                    // Use first 16 chars as key (sufficient for uniqueness, avoids full token in cache key)
                    return value.substring(0, Math.min(16, value.length()));
                }
            }
        }
        return null;
    }

    /** Clears all buckets — for tests only. */
    public void reset() {
        ipBuckets.invalidateAll();
        accountBuckets.invalidateAll();
        refreshBuckets.invalidateAll();
        dataEndpointBuckets.invalidateAll();
    }

    /**
     * Request wrapper that caches the body bytes (capped at MAX_LOGIN_BODY_BYTES),
     * allowing the body to be read multiple times (by this filter for username
     * extraction, then by the controller).
     *
     * HOLD-fix: oversized declared bodies are rejected with 413 BEFORE this wrapper
     * is created (see doFilterInternal), so the capped buffer only ever holds bodies
     * that legitimately fit. As defense in depth the wrapper itself stops reading at
     * MAX_LOGIN_BODY_BYTES+1 and flags the request as oversized (covers chunked /
     * no-Content-Length requests); doFilterInternal then rejects it with 413, so the
     * stream length always matches the buffered bytes (no length/stream desync).
     */
    private static class CachingRequestWrapper extends HttpServletRequestWrapper {
        private static final int READ_CAP = MAX_LOGIN_BODY_BYTES + 1;
        private final byte[] cachedBody;
        private final boolean oversized;

        CachingRequestWrapper(HttpServletRequest request) throws IOException {
            super(request);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(READ_CAP);
            byte[] buf = new byte[1024];
            int n;
            int total = 0;
            ServletInputStream inputStream = request.getInputStream();
            while (total <= READ_CAP && (n = inputStream.read(buf)) != -1) {
                int toWrite = Math.min(n, READ_CAP - total);
                baos.write(buf, 0, toWrite);
                total += toWrite;
                if (total > READ_CAP) {
                    break;
                }
            }
            this.oversized = total > MAX_LOGIN_BODY_BYTES;
            this.cachedBody = baos.toByteArray();
        }

        boolean isOversized() {
            return oversized;
        }

        byte[] getBodyBytes() {
            return cachedBody;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(cachedBody);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() { return byteArrayInputStream.available() == 0; }
                @Override
                public boolean isReady() { return true; }
                @Override
                public void setReadListener(ReadListener readListener) {}
                @Override
                public int read() { return byteArrayInputStream.read(); }
            };
        }

        @Override
        public BufferedReader getReader() {
            ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(cachedBody);
            return new BufferedReader(new InputStreamReader(byteArrayInputStream, StandardCharsets.UTF_8));
        }
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

        /** WO-SEC-44: roll back one token (when per-account limit rejects after IP limit passed). */
        synchronized void rollback() {
            tokens = Math.min(tokens + 1, capacity);
        }
    }
}
