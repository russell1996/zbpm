package com.zorrodev.bpm.rest.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * WO-SEC-64 (S-2): security headers on the Spring backend itself.
 *
 * <p>Not to be confused with the nginx headers of the frontend (WO-SEC-48,
 * already present): a direct-to-backend client never sees those. Same header
 * classes as nginx serves, set here for every backend response including
 * errors (filter runs for the whole chain, headers set before dispatch):
 * {@code X-Content-Type-Options: nosniff},
 * {@code X-Frame-Options: DENY},
 * {@code Referrer-Policy: no-referrer}.
 * No CSP: the backend serves JSON, not HTML — a script-src policy would be
 * cargo-cult. No HSTS: TLS terminates at nginx/proxy, backend is plain HTTP.
 */
public class SecurityHeadersFilter extends OncePerRequestFilter {

    /** First among app filters — headers present even when a later filter rejects. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 1;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        chain.doFilter(request, response);
    }
}
