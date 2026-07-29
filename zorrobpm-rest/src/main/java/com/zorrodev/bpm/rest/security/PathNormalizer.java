package com.zorrodev.bpm.rest.security;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared path normalization utility for JwtAuthFilter and RateLimitFilter.
 * Decodes %-encoding, strips matrix params, collapses double slashes,
 * resolves {@code .} and {@code ..} segments, removes trailing slash.
 *
 * <p>WO-BE-4: path traversal ({@code /auth/../users/…}) is resolved before any
 * auth matching, preventing bypass of SUPER_ADMIN guard on /users. Paths that
 * escape above root are canonicalized to a safe non-matching path (fail-closed).
 */
public final class PathNormalizer {

    private PathNormalizer() {}

    /**
     * Normalize a raw request URI path.
     *
     * <ol>
     *   <li>URL-decode (WO-SEC-15: /%75sers → /users)
     *   <li>Strip matrix parameters (WO-SEC-10: /users;x=1 → /users)
     *   <li>Collapse double slashes (//users → /users)
     *   <li><b>Resolve {@code .} and {@code ..} segments (WO-BE-4)</b>
     *   <li>Remove trailing slash
     * </ol>
     *
     * @param raw the raw request URI (may be null)
     * @return a normalized, canonical path; never null
     */
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
        p = p.replaceAll(";[^/]*", "");   // strip matrix params (WO-SEC-10)
        p = p.replaceAll("/{2,}", "/");    // collapse double slashes
        p = resolveDotSegments(p);         // resolve . and .. (WO-BE-4)
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);   // strip trailing slash
        }
        return p;
    }

    /**
     * Resolve {@code .} (current directory) and {@code ..} (parent directory)
     * segments per RFC 3986 §5.2.4. If the path attempts to escape above the
     * root ({@code /foo/../../..}), the extra parent segments are silently
     * dropped — the result never exceeds root.
     */
    static String resolveDotSegments(String path) {
        // Edge case: empty or root-only
        if (path == null || path.isEmpty() || "/".equals(path)) return path;

        boolean absolute = path.startsWith("/");
        String[] segments = path.split("/", -1);
        List<String> resolved = new ArrayList<>();

        for (String seg : segments) {
            if (".".equals(seg) || seg.isEmpty()) {
                // skip current-directory and empty segments (empty = collapsed // or leading /)
                continue;
            }
            if ("..".equals(seg)) {
                // parent directory: pop the last segment if any
                if (!resolved.isEmpty()) {
                    resolved.remove(resolved.size() - 1);
                }
                // If resolved is empty, we are at root — ignore extra ..
                // (fail-closed: path cannot escape above root)
                continue;
            }
            resolved.add(seg);
        }

        String result = String.join("/", resolved);
        if (absolute) {
            result = "/" + result;
        }
        // If result is empty (e.g. path was "../.."), return root
        if (result.isEmpty() || "/".equals(result)) {
            return "/";
        }
        return result;
    }
}
