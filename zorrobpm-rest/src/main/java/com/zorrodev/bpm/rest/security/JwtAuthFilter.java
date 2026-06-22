package com.zorrodev.bpm.rest.security;

import com.zorrodev.bpm.engine.security.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Validates the bearer token for identity/admin routes only ({@code /auth/me}, {@code /users/**}).
 * All other endpoints pass through untouched, so existing API clients are unaffected — locking the
 * data API down is a separate concern (the API-key plan). {@code /users/**} additionally requires ADMIN.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final TokenService tokenService;

    private static boolean isUsersPath(String path) {
        return path.equals("/users") || path.startsWith("/users/");
    }

    private static boolean isProtected(String path) {
        return path.equals("/auth/me") || isUsersPath(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
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
