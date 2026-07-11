package com.zorrodev.bpm.rest.security;

/**
 * Shared path normalization utility for JwtAuthFilter and RateLimitFilter.
 * Strips matrix params, collapses double slashes, removes trailing slash.
 */
public final class PathNormalizer {

    private PathNormalizer() {}

    public static String normalize(String raw) {
        if (raw == null) return "/";
        String p = raw.replaceAll(";[^/]*", "");   // strip matrix params
        p = p.replaceAll("/{2,}", "/");            // collapse double slashes
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);   // strip trailing slash
        }
        return p;
    }
}
