package com.zorrodev.bpm.rest.security;

import com.zorrodev.bpm.engine.security.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Validates the bearer token for identity/admin routes ({@code /auth/me}, {@code /users/**})
 * and, when {@code zorrobpm.security.require-api-auth=true}, for all data API endpoints as well.
 * {@code /auth/login} is always open (no token required).
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final TokenService tokenService;

    @Value("${zorrobpm.security.require-api-auth:true}")
    private boolean requireApiAuth;

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
     * Always protected: /auth/me, /users/**
     * Protected when requireApiAuth=true: /process-instances, /user-tasks, /variables,
     *   /incidents, /dmn, /timer-jobs, /message-subscriptions, /process-definitions, /service-tasks
     * Never protected: /auth/login
     */
    private boolean isProtected(String path) {
        if (isAuthLogin(path)) return false;
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
            || path.startsWith("/service-tasks");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        if (!isProtected(path)) {
            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        TokenService.Claims claims = (header != null && header.startsWith("Bearer "))
            ? tokenService.verify(header.substring(7))
            : null;

        if (claims == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
            return;
        }
        if (isUsersPath(path) && !"ADMIN".equals(claims.role())) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden");
            return;
        }

        request.setAttribute("authClaims", claims);
        chain.doFilter(request, response);
    }
}
