package com.zorrodev.bpm.rest.security;

import com.zorrodev.bpm.engine.entity.ApiKeyEntity;
import com.zorrodev.bpm.engine.repository.ApiKeyRepository;
import com.zorrodev.bpm.engine.security.KeyHasher;
import com.zorrodev.bpm.engine.security.TokenService;
import com.zorrodev.bpm.engine.service.PgRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
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
 * the filter trusts the connection remote address as the real client IP (set by nginx {@code real_ip_header}
 * + {@code set_real_ip_from}, WO-SEC-52). It does NOT parse the leftmost X-Forwarded-For entry, which an
 * upstream client can spoof (SEC-4). Untrusted XFF headers are ignored (WO-SEC-12/13 anti-spoofing preserved).
 *
 * <p>HOLD-fix (body-buffering DoS): per-IP check runs BEFORE any body read.
 * The body is only buffered (with a 16 KB cap) after the IP bucket allows the request.
 * Requests with a declared body larger than the cap are rejected with 413 BEFORE reading.
 * This prevents memory-exhaustion on the public unauthenticated /auth/login endpoint.
 *
 * <p>Runs with {@code HIGHEST_PRECEDENCE + 1} — before Spring's {@code ForwardedHeaderFilter}
 * ({@code HIGHEST_PRECEDENCE + 5}) to capture IP before XFF rewriting.
 *
 * <p>Cluster-safe: rate-limit state is stored in PostgreSQL via {@code PgRateLimiter}
 * (WO-SCALE-2), not in per-instance Caffeine caches.
 */
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    /** Maximum bytes buffered from login request body (login JSON is ~100 bytes). */
    static final int MAX_LOGIN_BODY_BYTES = 16_384;

    private PgRateLimiter pgRateLimiter;

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

    /** WO-SEC-58 HOLD-fix: verifies the access JWT to key /me/password per user. */
    private TokenService tokenService;

    void setTokenService(TokenService tokenService) { this.tokenService = tokenService; }

    void setPgRateLimiter(PgRateLimiter pgRateLimiter) { this.pgRateLimiter = pgRateLimiter; }

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

    // WO-AUDIT-4 (S4): order lives ONLY in RateLimitFilterConfig (FilterRegistrationBean).
    // The old getOrder() here disagreed with it (HIGHEST_PRECEDENCE + 1 vs HIGHEST_PRECEDENCE)
    // and was dead anyway — a FilterRegistrationBean-registered filter never consults Ordered.

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
            long ipRetryAfter = pgRateLimiter.tryConsume(ipKey, capacity, windowSeconds);
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
                String acctKey = "login:account:" + username.toLowerCase(java.util.Locale.ROOT);
                long acctRetryAfter = pgRateLimiter.tryConsume(acctKey, accountCapacity, windowSeconds);
                if (acctRetryAfter > 0) {
                    // Rollback IP bucket token — request rejected by account limit, not IP limit
                    pgRateLimiter.rollback(ipKey, capacity);
                    send429(response, acctRetryAfter);
                    return;
                }
            }

            chain.doFilter(wrappedRequest, response);
            return;
        }

        // WO-SEC-58: self-service password change — brute-force on the CURRENT
        // password must hit a login-strength limit.
        // HOLD-fix (P-63/P-65 class): the bucket is keyed on the USER resolved from
        // the access JWT, NOT on client IP. This filter runs before JwtAuthFilter,
        // so identity comes from verifying the token directly (same sources as
        // JwtAuthFilter: Bearer header, then zbpm_token cookie). Behind the shared
        // prod proxy every visitor presents the same address — an IP key here is ONE
        // bucket for the whole installation, and since this check runs BEFORE auth,
        // an anonymous flood would lock every user out of escaping
        // forcePasswordChange. With a user key a flood burns only the attacker's own
        // budget; requests without a valid token fall back to the IP key (they 401
        // downstream regardless).
        if ("PUT".equalsIgnoreCase(method) && "/me/password".equals(path)) {
            String userId = extractUserIdFromAccessJwt(request);
            // Authenticated → per-user bucket (real prod identity). Anonymous → one
            // shared bucket: without a valid JWT there is no per-client identity to
            // key on, and without trusted-proxies clientIp is the proxy's address
            // for everyone anyway — "anon" makes the shared-bucket nature explicit
            // and keeps G13 honest (no hidden per-IP bucket behind a proxy).
            String key = (userId != null) ? "me-password:user:" + userId : "me-password:anon";
            long retryAfter = pgRateLimiter.tryConsume(key, capacity, windowSeconds);
            if (retryAfter > 0) {
                send429(response, retryAfter);
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        // Refresh endpoint: per-user throttling
        if ("POST".equalsIgnoreCase(method) && "/auth/refresh".equals(path)) {
            String userId = extractUserIdFromRefreshCookie(request);
            if (userId != null) {
                String refreshKey = "refresh:" + userId;
                long retryAfter = pgRateLimiter.tryConsume(refreshKey, refreshCapacity, refreshWindowSeconds);
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
            // WO-INT-4 criterion 8: quota is counted per API KEY, not per IP — an
            // integration BFF calling from one address must not be throttled by another
            // BFF on the same address (and must not exhaust the shared per-IP quota).
            String key = resolveDataBucketKey(request, clientIp);
            long retryAfter = pgRateLimiter.tryConsume(key, dataCapacity, dataWindowSeconds);
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
        // WO-SEC-64 (S-16): all mutating methods share the data bucket — PUT/PATCH/DELETE
        // used to bypass the limit entirely (GET/POST only). Reads stay covered as before.
        if ("GET".equalsIgnoreCase(method) || "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method)) {
            return path.startsWith("/events") || path.startsWith("/variables")
                || path.startsWith("/process-instances") || path.startsWith("/user-tasks")
                || path.startsWith("/service-tasks") || path.startsWith("/incidents")
                || path.startsWith("/process-definitions");
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
     * WO-SEC-44/SEC-52: extract client IP, respecting trusted proxy configuration.
     * <p>
     * When the immediate peer is a trusted proxy (e.g. nginx with {@code real_ip_header
     * X-Forwarded-For} + {@code set_real_ip_from}), the proxy has already rewritten the
     * connection's remote address to the real client IP — so we trust {@code remoteAddr}
     * directly. Reading the LEFTMOST X-Forwarded-For entry (the old behaviour, SEC-4) is
     * spoofable: an upstream client can prepend arbitrary addresses to XFF, and the
     * leftmost is exactly the attacker-controlled one, letting a client dodge/forge its
     * rate-limit bucket. Without a trusted proxy in front, {@code remoteAddr} is the
     * direct client — also correct.
     */
    public String getClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (trustedProxies.isEmpty() || !isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }
        // Trusted proxy in front: rely on it having resolved the real client IP into the
        // connection remote address (nginx real_ip). Do NOT parse XFF leftmost (SEC-4).
        return remoteAddr;
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

    /**
     * WO-SEC-58 HOLD-fix: resolve the authenticated user for /me/password bucketing.
     * Same token sources as JwtAuthFilter (Bearer header, then zbpm_token cookie);
     * signature is verified via TokenService, so a client cannot pick an arbitrary
     * userId key. API keys and invalid/absent tokens → null → caller falls back to
     * the per-IP key. Never throws into the chain.
     */
    private String extractUserIdFromAccessJwt(HttpServletRequest request) {
        if (tokenService == null) return null;
        String header = request.getHeader("Authorization");
        String token = (header != null && header.startsWith("Bearer ")) ? header.substring(7) : null;
        if (token == null) {
            Cookie[] cookies = request.getCookies();
            if (cookies != null) {
                for (Cookie cookie : cookies) {
                    if ("zbpm_token".equals(cookie.getName())) {
                        token = cookie.getValue();
                        break;
                    }
                }
            }
        }
        if (token == null || token.isBlank() || token.startsWith(JwtAuthFilter.API_KEY_PREFIX)) {
            return null;
        }
        try {
            TokenService.Claims claims = tokenService.verify(token);
            return claims != null ? claims.userId().toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Clears all bucket state — for tests only. Delegates to PgRateLimiter. */
    public void reset() {
        pgRateLimiter.reset();
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

}
