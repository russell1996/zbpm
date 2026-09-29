package com.zorrodev.bpm.rest.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * WO-SEC-64 (S-3): same-origin CSRF guard for the cookie-auth path.
 *
 * <p>Applies ONLY when the request authenticates via the access cookie
 * ({@code __Host-zbpm_token}, legacy {@code zbpm_token}) WITHOUT a Bearer
 * header: Bearer/API-key requests are not browser-ambient credentials and are
 * not CSRF-able by nature — they pass through untouched (criterion 2). Safe
 * methods (GET/HEAD/OPTIONS/TRACE) pass through: CSRF needs a state-changing
 * request. Public login/refresh paths pass through (no ambient session yet).
 *
 * <p>Rule: mutating methods (POST/PUT/PATCH/DELETE) with cookie auth require
 * an {@code Origin} (preferred) or {@code Referer} header whose origin matches
 * the request's own origin (scheme + host + port). Absent or foreign origin →
 * 403. Same-origin fetch/XHR always send {@code Origin} on mutating methods,
 * so legit SPA traffic is unaffected (criterion 1).
 *
 * <p>Runs BEFORE {@code JwtAuthFilter} in the chain ({@code ORDER =
 * LOWEST_PRECEDENCE - 100} sorts earlier than the plain-component default) —
 * it does not authenticate, it only checks the raw transport (cookie present
 * + no Bearer header). CORS preflights (OPTIONS) are passed through by both
 * filters independently.
 */
@Slf4j
public class CsrfFilter extends OncePerRequestFilter {

    /** WO-REL-32: between JwtAuth (HIGHEST+20) and Idempotency (HIGHEST+30) — auth before CSRF before replay. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 25;

    static final String ACCESS_COOKIE = "__Host-zbpm_token";
    static final String LEGACY_ACCESS_COOKIE = "zbpm_token";

    private final Set<String> trustedOrigins;

    public CsrfFilter(
            @Value("${zorrobpm.cors.allowed-origins:http://localhost:5173,http://localhost:3000}") String allowedOrigins) {
        this.trustedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(CsrfFilter::normalizeOrigin)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!needsCheck(request)) {
            chain.doFilter(request, response);
            return;
        }
        String origin = originOf(request.getHeader("Origin"));
        if (origin == null) {
            origin = originOf(request.getHeader("Referer"));
        }
        String own = ownOrigin(request);
        if (origin == null || (!origin.equals(own) && !trustedOrigins.contains(origin))) {
            log.warn("CSRF rejected: {} {} cookie-auth with origin {} (own {})",
                    request.getMethod(), request.getRequestURI(),
                    origin != null ? origin : "<absent>", own);
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "CSRF check failed");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean needsCheck(HttpServletRequest request) {
        String method = request.getMethod();
        if (!("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method))) {
            return false;
        }
        String path = PathNormalizer.normalize(request.getRequestURI());
        // No ambient session on these paths yet — nothing to forge.
        // (WO-SEC-64 HOLD: earlier carve-outs for /process-instances and
        // /auth/logout removed — Idempotency-Key is OPTIONAL (IdempotencyFilter
        // passes keyless requests straight through), so it cannot substitute a
        // CSRF layer; logout-CSRF is a real nuisance. Existing tests now send
        // Origin like the SPA does.)
        if (JwtAuthFilter.isPublicPath(path) || "/auth/me".equals(path)) {
            return false;
        }
        // Bearer present → not cookie-auth → not CSRF-able.
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return false;
        }
        return hasAccessCookie(request);
    }

    private boolean hasAccessCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return false;
        for (Cookie cookie : cookies) {
            if (ACCESS_COOKIE.equals(cookie.getName()) || LEGACY_ACCESS_COOKIE.equals(cookie.getName())) {
                return true;
            }
        }
        return false;
    }

    static String ownOrigin(HttpServletRequest request) {
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        boolean defaultPort = ("http".equalsIgnoreCase(scheme) && port == 80)
                || ("https".equalsIgnoreCase(scheme) && port == 443);
        return scheme.toLowerCase(java.util.Locale.ROOT) + "://" + host.toLowerCase(java.util.Locale.ROOT) + (defaultPort ? "" : ":" + port);
    }

    /** Origin header value → normalized scheme://host[:port]; Referer → its origin part. Null when unparsable. */
    static String originOf(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) return null;
        try {
            URI uri = new URI(headerValue.trim());
            if (uri.getScheme() == null || uri.getHost() == null) return null;
            int port = uri.getPort();
            boolean defaultPort = ("http".equalsIgnoreCase(uri.getScheme()) && (port == 80 || port == -1))
                    || ("https".equalsIgnoreCase(uri.getScheme()) && (port == 443 || port == -1));
            return uri.getScheme().toLowerCase(java.util.Locale.ROOT) + "://" + uri.getHost().toLowerCase(java.util.Locale.ROOT)
                    + (defaultPort ? "" : ":" + port);
        } catch (Exception e) {
            return null;
        }
    }

    static String normalizeOrigin(String origin) {
        String n = originOf(origin);
        return n != null ? n : origin.trim();
    }
}
