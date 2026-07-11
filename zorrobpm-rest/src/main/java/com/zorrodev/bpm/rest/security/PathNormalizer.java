package com.zorrodev.bpm.rest.security;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Shared path normalization utility for JwtAuthFilter and RateLimitFilter.
 * Decodes %-encoding, strips matrix params, collapses double slashes, removes trailing slash.
 */
public final class PathNormalizer {

    private PathNormalizer() {}

    public static String normalize(String raw) {
        if (raw == null) return "/";
        // Decode %-encoding first (WO-SEC-15: /%75sers → /users)
        String p;
        try {
            p = URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // Malformed encoding — treat as-is (will likely fail auth)
            p = raw;
        }
        p = p.replaceAll(";[^/]*", "");   // strip matrix params
        p = p.replaceAll("/{2,}", "/");    // collapse double slashes
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);   // strip trailing slash
        }
        return p;
    }
}
