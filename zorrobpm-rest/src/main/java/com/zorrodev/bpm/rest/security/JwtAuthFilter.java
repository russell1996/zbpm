package com.zorrodev.bpm.rest.security;

import com.zorrodev.bpm.engine.entity.ServiceAccountEntity;
import com.zorrodev.bpm.engine.entity.ServiceAccountPermissionEntity;
import com.zorrodev.bpm.engine.repository.ServiceAccountPermissionRepository;
import com.zorrodev.bpm.engine.repository.ServiceAccountRepository;
import com.zorrodev.bpm.engine.security.KeyHasher;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.security.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String API_KEY_PREFIX = "zbpm_sk_";

    private final TokenService tokenService;
    private final ServiceAccountRepository serviceAccountRepository;
    private final ServiceAccountPermissionRepository saPermissionRepository;

    @Value("${zorrobpm.security.require-api-auth:true}")
    private boolean requireApiAuth;

    public JwtAuthFilter(TokenService tokenService,
                         ServiceAccountRepository serviceAccountRepository,
                         ServiceAccountPermissionRepository saPermissionRepository) {
        this.tokenService = tokenService;
        this.serviceAccountRepository = serviceAccountRepository;
        this.saPermissionRepository = saPermissionRepository;
    }

    void setRequireApiAuth(boolean requireApiAuth) {
        this.requireApiAuth = requireApiAuth;
    }

    private static String normalizePath(String raw) {
        if (raw == null) return "/";
        String p = raw.replaceAll(";[^/]*", "");
        p = p.replaceAll("/{2,}", "/");
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    private static boolean isUsersPath(String path) {
        return path.equals("/users") || path.startsWith("/users/");
    }

    private static boolean isAuthLogin(String path) {
        return path.equals("/auth/login");
    }

    private boolean isProtected(String path) {
        if (isAuthLogin(path) || "/auth/refresh".equals(path)) return false;
        if (path.equals("/auth/me") || path.equals("/auth/logout") || isUsersPath(path)) return true;
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
            || path.startsWith("/service-tasks");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String path = normalizePath(request.getRequestURI());
        if (!isProtected(path)) {
            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        String token = (header != null && header.startsWith("Bearer "))
            ? header.substring(7)
            : null;

        // API key path: use Bearer zbpm_sk_ directly
        // JWT path: use Bearer JWT, or fall back to cookie
        if (token == null || (!token.startsWith(API_KEY_PREFIX))) {
            // Not an API key — try cookie for JWT
            String cookieToken = extractTokenFromCookie(request);
            token = (cookieToken != null) ? cookieToken : token;
        }

        Principal principal = resolvePrincipal(token);

        if (principal == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
            return;
        }

        // Legacy /users path guard
        boolean isUsersAllowed = principal.isSuperAdmin()
            || (principal instanceof Principal.UserPrincipal u && "ADMIN".equals(u.globalRole()));
        if (isUsersPath(path) && !isUsersAllowed) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden");
            return;
        }

        request.setAttribute("principal", principal);

        // Backward compat: also set authClaims for code that still reads it
        if (principal instanceof Principal.UserPrincipal) {
            TokenService.Claims claims = tokenService.verify(token);
            if (claims != null) {
                request.setAttribute("authClaims", claims);
            }
        }

        chain.doFilter(request, response);
    }

    Principal resolvePrincipal(String token) {
        if (token == null) return null;

        // API key path
        if (token.startsWith(API_KEY_PREFIX)) {
            String keyHash = KeyHasher.sha256(token);
            var candidates = serviceAccountRepository.findByKeyHash(keyHash);
            if (candidates.isEmpty()) {
                log.debug("Unknown API key prefix: {}", token.substring(0, Math.min(token.length(), 16)));
                return null;
            }
            ServiceAccountEntity sa = candidates.get(0);

            if (sa.getRevokedAt() != null) {
                log.debug("Revoked API key used: prefix={}", sa.getPrefix());
                return null;
            }
            if (sa.getExpiresAt() != null && sa.getExpiresAt().isBefore(Instant.now())) {
                log.debug("Expired API key used: prefix={}", sa.getPrefix());
                return null;
            }

            Set<String> permissions = loadPermissions(sa.getId());

            sa.setLastUsedAt(Instant.now());
            serviceAccountRepository.save(sa);

            return new Principal.ServicePrincipal(sa.getId(), sa.getProcessId(), permissions);
        }

        // JWT path
        TokenService.Claims claims = tokenService.verify(token);
        if (claims == null) return null;
        return new Principal.UserPrincipal(claims.userId(), claims.username(), claims.role());
    }

    private Set<String> loadPermissions(UUID serviceAccountId) {
        return saPermissionRepository.findByServiceAccountId(serviceAccountId).stream()
            .map(ServiceAccountPermissionEntity::getPermission)
            .collect(Collectors.toSet());
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
