package com.zorrodev.bpm.rest.security;

import com.zorrodev.bpm.engine.entity.ApiKeyEntity;
import com.zorrodev.bpm.engine.entity.ApiKeyGrantEntity;
import com.zorrodev.bpm.engine.repository.ApiKeyGrantRepository;
import com.zorrodev.bpm.engine.repository.ApiKeyRepository;
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
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String API_KEY_PREFIX = "zbpm_sk_";

    private final TokenService tokenService;
    private final ApiKeyRepository apiKeyRepository;
    private final ApiKeyGrantRepository apiKeyGrantRepository;
    private final UiUserLookupService userLookupService;
    private final Environment environment;

    @Value("${zorrobpm.security.require-api-auth:true}")
    private boolean requireApiAuth;

    @Value("${zorrobpm.security.force-password-enforce:false}")
    private boolean forcePasswordEnforce;

    public JwtAuthFilter(TokenService tokenService,
                         ApiKeyRepository apiKeyRepository,
                         ApiKeyGrantRepository apiKeyGrantRepository,
                         UiUserLookupService userLookupService,
                         Environment environment) {
        this.tokenService = tokenService;
        this.apiKeyRepository = apiKeyRepository;
        this.apiKeyGrantRepository = apiKeyGrantRepository;
        this.userLookupService = userLookupService;
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
     * /users/* is where password change happens (PUT /users/{id}).
     */
    private static boolean isAuthExempt(String path) {
        return isAuthLogin(path)
            || "/auth/refresh".equals(path)
            || "/auth/logout".equals(path)
            || "/auth/me".equals(path)
            || isUsersPath(path);
    }

    private boolean isProtected(String path) {
        if (isAuthLogin(path) || "/auth/refresh".equals(path)) return false;
        // WO-SEC-18 L6: logout accessible without valid access token (refresh token identifies user)
        if (path.equals("/auth/me") || isUsersPath(path)) return true;
        if (!requireApiAuth) return false;
        return isDataApiPath(path);
    }

    private static boolean isDataApiPath(String path) {
        return path.startsWith("/process-instances")
            || path.startsWith("/user-tasks")
            || path.startsWith("/variables")
            || path.startsWith("/incidents")
            || path.equals("/dmn") || path.startsWith("/dmn/")
            || path.startsWith("/timer-jobs")
            || path.startsWith("/message-subscriptions")
            || path.startsWith("/process-definitions")
            || path.startsWith("/service-tasks")
            || path.startsWith("/processes/")
            || path.startsWith("/admin/")
            || path.equals("/forms") || path.startsWith("/forms/")
            || path.equals("/me/api-key") || path.startsWith("/me/api-key/")
            || path.equals("/events") || path.startsWith("/events/");
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
        Principal principal;

        if (token != null && token.startsWith(API_KEY_PREFIX)) {
            principal = resolveApiKey(token);
        } else if (token != null) {
            claims = tokenService.verify(token);
            principal = (claims != null) ? new Principal.UserPrincipal(claims.userId(), claims.username(), claims.role()) : null;
        } else {
            principal = null;
        }

        if (principal == null) {
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
            if (forcePasswordEnforce && !isAuthExempt(reqPath)
                && userLookupService.isForcePasswordChange(userPrincipal.userId())) {
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

        // Load grants
        Map<UUID, Principal.Grant> grants = loadGrants(apiKey.getId());
        apiKey.setLastUsedAt(Instant.now());
        apiKeyRepository.save(apiKey);

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

    private String extractTokenFromCookie(HttpServletRequest request) {
        jakarta.servlet.http.Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (jakarta.servlet.http.Cookie cookie : cookies) {
            if ("zbpm_token".equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
