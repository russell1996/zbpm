package com.zorrodev.bpm.rest.security;

import com.zorrodev.bpm.engine.entity.ApiKeyEntity;
import com.zorrodev.bpm.engine.entity.ApiKeyGrantEntity;
import com.zorrodev.bpm.engine.repository.ApiKeyGrantRepository;
import com.zorrodev.bpm.engine.repository.ApiKeyRepository;
import com.zorrodev.bpm.engine.security.AuthorizationService;
import com.zorrodev.bpm.engine.security.KeyHasher;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.security.TokenService;
import com.zorrodev.bpm.engine.security.UiUserLookupService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class JwtAuthFilter extends OncePerRequestFilter {

    // package-visible: RateLimitFilter reuses the prefix to skip API keys when
    // resolving the /me/password bucket owner (WO-SEC-58 HOLD-fix).
    static final String API_KEY_PREFIX = "zbpm_sk_";
    /** WO-SEC-34: debounce interval — don't update lastUsedAt more than once per 5 minutes */
    private static final long DEBOUNCE_MS = 5 * 60 * 1000L;

    private final TokenService tokenService;
    private final ApiKeyRepository apiKeyRepository;
    private final ApiKeyGrantRepository apiKeyGrantRepository;
    private final UiUserLookupService userLookupService;
    private final AuthorizationService authorizationService;
    private final Environment environment;
    /** WO-SEC-34: in-memory debounce tracker — apiKeyId → last write timestamp (bounded) */
    private final Cache<UUID, Instant> lastWriteTimestamps = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterAccess(Duration.ofMinutes(10))
        .build();

    @Value("${zorrobpm.security.require-api-auth:true}")
    private boolean requireApiAuth;

    @Value("${zorrobpm.security.force-password-enforce:false}")
    private boolean forcePasswordEnforce;

    public JwtAuthFilter(TokenService tokenService,
                         ApiKeyRepository apiKeyRepository,
                         ApiKeyGrantRepository apiKeyGrantRepository,
                         UiUserLookupService userLookupService,
                         AuthorizationService authorizationService,
                         Environment environment) {
        this.tokenService = tokenService;
        this.apiKeyRepository = apiKeyRepository;
        this.apiKeyGrantRepository = apiKeyGrantRepository;
        this.userLookupService = userLookupService;
        this.authorizationService = authorizationService;
        this.environment = environment;
    }

    void setRequireApiAuth(boolean requireApiAuth) {
        this.requireApiAuth = requireApiAuth;
    }

    private static boolean isUsersPath(String path) {
        return path.equals("/users") || path.startsWith("/users/");
    }

    private static boolean isAuthLogin(String path) {
        return path.equals("/auth/login");
    }

    /**
     * WO-SEC-14: Paths exempt from forcePasswordChange enforcement.
     * /auth/me is read-only (returns user info) — exempt.
     * WO-OBS-4: /auth/verify is read-only auth-introspection (identity headers only) — exempt,
     * same reason as /auth/me (nginx must validate any live session).
     * /users/* is where password change happens (PUT /users/{id}).
     */
    private static boolean isAuthExempt(String path) {
        return isAuthLogin(path)
            || "/auth/refresh".equals(path)
            || "/auth/logout".equals(path)
            || "/auth/me".equals(path)
            || "/auth/verify".equals(path)
            || isUsersPath(path)
            || "/me/password".equals(path);
    }

    /**
     * WO-SEC-43: distinguishes a real top-level browser navigation (typed URL, clicked link -
     * e.g. hitting /swagger-ui/index.html directly) from an API/XHR call (the SPA's own axios
     * requests). Sec-Fetch-Mode is sent automatically by all modern browsers and is not
     * spoofable by page JS, so it's a reliable signal here - "navigate" only occurs for actual
     * document loads, never for fetch/XHR. GET-only because navigations are always GET; a
     * misconfigured or legacy client without Sec-Fetch-Mode just falls through to the existing
     * JSON 401 (unchanged behavior), it never gets redirected by accident.
     */
    private static boolean isBrowserNavigation(HttpServletRequest request) {
        return "GET".equalsIgnoreCase(request.getMethod())
            && "navigate".equals(request.getHeader("Sec-Fetch-Mode"));
    }

    /**
     * WO-SEC-43: the path the user was actually trying to reach (e.g. /swagger-ui/index.html),
     * URL-encoded for use as a query param on the /ui/login redirect. Without this, Login.vue's
     * post-login redirect defaults to '/' - sending someone who was on Swagger straight to the
     * SPA dashboard instead of back to where they were.
     */
    private static String encodeRedirectTarget(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String query = request.getQueryString();
        String target = (query != null) ? uri + "?" + query : uri;
        return java.net.URLEncoder.encode(target, java.nio.charset.StandardCharsets.UTF_8);
    }

    private boolean isProtected(String path) {
        // WO-SEC-26: deny-by-default — only explicitly public paths are unprotected
        if (isPublicPath(path)) return false;
        // /auth/me, /auth/verify and /users/* always require auth, even when requireApiAuth=false
        if (path.equals("/auth/me") || path.equals("/auth/verify") || path.startsWith("/me/") || isUsersPath(path)) return true;
        if (!requireApiAuth) return false;
        return true;
    }

    static boolean isPublicPath(String path) {
        return isAuthLogin(path)
            || "/auth/refresh".equals(path)
            || "/auth/forgot-password".equals(path)
            || "/auth/reset-password".equals(path)
            || "/auth/accept-invitation".equals(path)
            || "/auth/register".equals(path)
            || "/auth/verify-email".equals(path)
            || "/error".equals(path)
            || path.startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String path = PathNormalizer.normalize(request.getRequestURI());

        // WO-SEC-18 L6: logout needs identity but not auth — parse JWT if present, don't reject
        if (path.equals("/auth/logout")) {
            String header = request.getHeader("Authorization");
            String token = (header != null && header.startsWith("Bearer "))
                ? header.substring(7)
                : extractTokenFromCookie(request);
            if (token != null && !token.startsWith(API_KEY_PREFIX)) {
                TokenService.Claims claims = tokenService.verify(token);
                if (claims != null) {
                    request.setAttribute("authClaims", claims);
                    request.setAttribute("principal", new Principal.UserPrincipal(claims.userId(), claims.username(), claims.role()));
                }
            }
            chain.doFilter(request, response);
            return;
        }

        if (!isProtected(path)) {
            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        String token = (header != null && header.startsWith("Bearer "))
            ? header.substring(7)
            : null;

        // #3: Bearer header takes priority for both JWT and API key.
        // Cookie is fallback ONLY when no Bearer header is present.
        if (token == null) {
            token = extractTokenFromCookie(request);
        }

        // Resolve principal — may verify token (JWT or API key)
        TokenService.Claims claims = null;
        // WO-SEC-63: the per-request user-state projection loaded for the JWT path
        // (active/role/tokenVersion). Reused later for the forcePasswordChange check,
        // so the whole filter does at most ONE indexed PK lookup per JWT request.
        UiUserLookupService.UserSecurityState jwtState = null;
        Principal principal;

        if (token != null && token.startsWith(API_KEY_PREFIX)) {
            principal = resolveApiKey(token);
        } else if (token != null) {
            claims = tokenService.verify(token);
            if (claims != null) {
                // WO-SEC-63 (F01): the JWT path now performs the same live user-state check the
                // API-key path already does (WO-ACL-5). A token is valid ONLY if the user still
                // exists, is active, and the claim version matches the current token_version
                // (logout / password change bumps it → all previously issued access tokens die).
                // Claim role is compared as defense-in-depth: a role change bumps the version on
                // write, so mismatch here indicates a missed bump — fail closed, never use a stale
                // role beyond its access TTL.
                jwtState = userLookupService.securityState(claims.userId()).orElse(null);
                if (jwtState == null || !jwtState.active()
                    || jwtState.tokenVersion() != claims.tokenVersion()
                    || !jwtState.role().equals(claims.role())) {
                    claims = null;
                }
            }
            principal = (claims != null) ? new Principal.UserPrincipal(claims.userId(), claims.username(), claims.role()) : null;
        } else {
            principal = null;
        }

        if (principal == null) {
            if (isBrowserNavigation(request)) {
                response.sendRedirect("/ui/login?redirect=" + encodeRedirectTarget(request));
                return;
            }
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
            return;
        }

        // ADR-2: /users path guard — SUPER_ADMIN only
        if (isUsersPath(path) && !principal.isSuperAdmin()) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden");
            return;
        }

        request.setAttribute("principal", principal);

        // WO-SEC-14: forcePasswordChange enforcement for UserPrincipal
        // Enforced only when zorrobpm.security.force-password-enforce=true
        // (prod default: true; test default: false unless overridden)
        if (principal instanceof Principal.UserPrincipal userPrincipal) {
            String reqPath = PathNormalizer.normalize(request.getRequestURI());
            // WO-SEC-63: jwtState was already loaded for the version/active/role check above —
            // reuse it instead of the previous separate isForcePasswordChange repository lookup.
            boolean forceChange = jwtState != null && jwtState.forcePasswordChange();
            if (forcePasswordEnforce && !isAuthExempt(reqPath) && forceChange) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                response.getWriter().write("{\"code\":\"PASSWORD_CHANGE_REQUIRED\",\"message\":\"Password change required\"}");
                return;
            }
        }

        // Backward compat: also set authClaims for code that still reads it
        if (claims != null) {
            request.setAttribute("authClaims", claims);
        }

        chain.doFilter(request, response);
    }

    /**
     * ADR-2: resolve API key using api_key + api_key_grant tables.
     * One key per user → grants map (processId → {permissions, isFull}).
     */
    private Principal resolveApiKey(String token) {
        String keyHash = KeyHasher.sha256(token);
        var keyOpt = apiKeyRepository.findByKeyHash(keyHash);
        if (keyOpt.isEmpty()) {
            log.debug("Unknown API key"); // #2: never log token substring
            return null;
        }
        ApiKeyEntity apiKey = keyOpt.get();

        if (apiKey.getRevokedAt() != null) {
            log.debug("Revoked API key used: prefix={}", apiKey.getPrefix());
            return null;
        }
        if (apiKey.getExpiresAt() != null && apiKey.getExpiresAt().isBefore(Instant.now())) {
            log.debug("Expired API key used: prefix={}", apiKey.getPrefix());
            return null;
        }

        // WO-ACL-5 criterion #4: a deactivated (or deleted) owner's key stops working —
        // checked on every request, not only when the key was issued.
        if (!userLookupService.isActive(apiKey.getOwnerUserId())) {
            log.debug("API key owner deactivated: prefix={}", apiKey.getPrefix());
            return null;
        }

        // WO-ACL-5 (ADR-8 п.5): effective grants = key grants ∩ the owner's CURRENT
        // process rights — membership/role changes narrow the key immediately (criterion #3),
        // and no grant can ever exceed what the owner could do directly (criterion #6).
        Map<UUID, Principal.Grant> grants =
            authorizationService.effectiveGrants(apiKey.getOwnerUserId(), loadGrants(apiKey.getId()));

        // WO-SEC-34: debounce — only update lastUsedAt if stale (>DEBOUNCE_MS since last write).
        // WO-SEC-66 (F07): conditional single-column UPDATE — never a full-entity
        // save. A concurrent revoke between our read above and this write must
        // NOT merge the stale detached snapshot (revokedAt=null) back over it.
        Instant now = Instant.now();
        Instant lastWrite = lastWriteTimestamps.getIfPresent(apiKey.getId());
        if (lastWrite == null || now.toEpochMilli() - lastWrite.toEpochMilli() > DEBOUNCE_MS) {
            apiKeyRepository.touchLastUsedAtIfLive(apiKey.getId(), now);
            lastWriteTimestamps.put(apiKey.getId(), now);
        }

        return new Principal.ServicePrincipal(apiKey.getId(), apiKey.getOwnerUserId(), grants);
    }

    private Map<UUID, Principal.Grant> loadGrants(UUID apiKeyId) {
        return apiKeyGrantRepository.findByApiKeyId(apiKeyId).stream()
            .collect(Collectors.toMap(
                ApiKeyGrantEntity::getProcessId,
                g -> new Principal.Grant(
                    g.getPermissions() != null
                        ? Set.of(g.getPermissions().split(","))
                        : Set.of(),
                    g.isFull()
                )
            ));
    }

    /**
     * WO-SEC-71: single resolver for the access-token cookie, shared with
     * {@code RateLimitFilter} so rate-limit identity can never drift from auth
     * identity again (N04: the filter read only the legacy name while auth had
     * already moved to {@code __Host-}). Package-visible static — no behavior
     * change for this filter (order/config untouched).
     */
    static String extractTokenFromCookie(HttpServletRequest request) {
        jakarta.servlet.http.Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        String legacy = null;
        for (jakarta.servlet.http.Cookie cookie : cookies) {
            // WO-SEC-64 (S-3): __Host- name first; legacy zbpm_token accepted as
            // fallback during rotation (set nowhere new since SEC-64).
            if ("__Host-zbpm_token".equals(cookie.getName())) {
                return cookie.getValue();
            }
            if ("zbpm_token".equals(cookie.getName())) {
                legacy = cookie.getValue();
            }
        }
        return legacy;
    }
}
