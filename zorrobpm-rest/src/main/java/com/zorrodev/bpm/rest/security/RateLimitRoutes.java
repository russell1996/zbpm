package com.zorrodev.bpm.rest.security;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * WO-AUDIT-13 (F-06): the single source of truth for "which rate-limit bucket
 * does this request belong to".
 *
 * <p>Before this class the answer lived in TWO places that had to be kept in
 * sync by hand: {@code RateLimitFilterConfig.rateLimitFilterRegistration}
 * (the servlet URL patterns — the filter only runs for registered paths) and
 * {@code RateLimitFilter.isDataEndpoint} (the in-filter guard that picks the
 * bucket). The audit found the two lists out of sync with the real route
 * surface: {@code /process-submissions*}, {@code /presets*}, {@code /messages*},
 * {@code /timer-jobs*}, {@code /message-subscriptions*},
 * {@code /auth/reset-password}, {@code /auth/accept-invitation},
 * {@code /auth/verify-email} were in NEITHER list, so those routes had no
 * bucket at all (F-06, docs/audit/ZorroBPM-full-audit-2026-10-19.md).
 *
 * <p>Both halves are now derived from {@link #ROUTES}:
 * <ul>
 *   <li>{@link #registrationPatterns()} — the servlet URL patterns the filter is
 *       registered for (a route with no pattern is dead code: the filter never
 *       runs for it);</li>
 *   <li>{@link #resolve(String, String)} — the bucket a request is charged to
 *       (the old {@code isDataEndpoint}).</li>
 * </ul>
 * A new route can therefore no longer be half-registered: the guard test
 * ({@code Audit13RateLimitRouteMatrixIT}) walks the LIVE route table of the
 * running application and fails for any mutating route that is neither in the
 * matrix nor covered by the registration.
 *
 * <p>Order matters: the first matching route wins. The exact-path buckets
 * (login/refresh/me-password/SSE/auth-link) are declared BEFORE the prefix
 * routes, so {@code PUT /me/password} is charged to the me-password bucket and
 * not to the generic {@code /me} data prefix.
 */
public final class RateLimitRoutes {

    private RateLimitRoutes() {
    }

    /** The rate-limit bucket a request is charged to. */
    public enum Bucket {
        /** {@code POST /auth/login} — per-IP AND per-account. */
        LOGIN,
        /** {@code POST /auth/refresh} — per-IP AND per-user. */
        REFRESH,
        /** {@code PUT /me/password} — per authenticated user. */
        ME_PASSWORD,
        /** {@code GET /events/stream} — per connection, not a REST call. */
        SSE,
        /**
         * Public, unauthenticated password-link endpoints (register /
         * forgot-password / reset-password / accept-invitation /
         * verify-email). Their own bucket, NOT the data bucket: they are
         * reachable without any credential, so sharing the data bucket would
         * let a flood of them eat the budget of every legitimate API client
         * behind the same address (P-63 class of bug).
         */
        AUTH_LINK,
        /** Everything else that mutates or reads business data. */
        DATA
    }

    /** How a route's path is matched against the normalized request path. */
    public enum Match {
        /** The normalized path must equal the route value. */
        EXACT,
        /** The normalized path must start with the route value. */
        PREFIX
    }

    /**
     * One line of the matrix: a bucket, how the path is matched, which HTTP
     * methods it applies to and the servlet URL patterns that make the filter
     * actually run for it.
     */
    public record Route(Bucket bucket, Match match, String value, Set<String> methods, List<String> urlPatterns) {

        /** @return true when this route claims the given (normalized) request. */
        public boolean matches(String method, String path) {
            if (!methods.contains(method)) {
                return false;
            }
            return switch (match) {
                case EXACT -> value.equals(path);
                case PREFIX -> path.startsWith(value);
            };
        }
    }

    /**
     * The data bucket covers the same methods as before the matrix existed
     * (WO-SEC-64): GET/POST/PUT/PATCH/DELETE. HEAD/OPTIONS stay out — OPTIONS
     * is the CORS preflight and must not consume a token.
     */
    private static final Set<String> DATA_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

    /**
     * The matrix. Every entry is either an exact path (own bucket) or a
     * prefix (the data bucket).
     */
    private static final List<Route> ROUTES = List.of(
        // ---------------- exact paths, own bucket ----------------
        exact(Bucket.LOGIN, "/auth/login", "POST"),
        exact(Bucket.REFRESH, "/auth/refresh", "POST"),
        exact(Bucket.ME_PASSWORD, "/me/password", "PUT"),
        exact(Bucket.SSE, "/events/stream", "GET", "/events/*"),

        // ---------------- public password-link endpoints ----------------
        // (F-06): register/forgot-password/reset-password/accept-invitation/
        // verify-email were outside every bucket. They are public and
        // unauthenticated, so they get their own per-IP bucket instead of
        // sharing the data budget.
        exact(Bucket.AUTH_LINK, "/auth/register", "POST"),
        exact(Bucket.AUTH_LINK, "/auth/forgot-password", "POST"),
        exact(Bucket.AUTH_LINK, "/auth/reset-password", "POST"),
        exact(Bucket.AUTH_LINK, "/auth/accept-invitation", "POST"),
        exact(Bucket.AUTH_LINK, "/auth/verify-email", "POST"),

        // ---------------- authenticated data surface ----------------
        // POST /auth/logout: a mutation like any other — before the matrix it
        // was reachable without a limit (found while building the matrix).
        exact(Bucket.DATA, "/auth/logout", "POST"),

        data("/events"),
        data("/variables"),
        data("/process-instances"),
        data("/user-tasks"),
        data("/service-tasks"),
        data("/incidents"),
        data("/process-definitions"),
        data("/deployments"),
        data("/users"),
        data("/dmn"),
        data("/forms"),
        data("/me/api-key"),
        // /me/memberships was an explicit registration before the matrix and
        // stays an explicit line so the servlet pattern set does not change.
        data("/me/memberships"),
        // The whole /me subtree (catch-all) — "/me/*" was registered before
        // the matrix as well.
        data("/me"),
        data("/variable-schemas"),
        // The whole /processes subtree: before the matrix the guard matched
        // only contains("/members"), so POST /processes/{key}/archive and
        // /unarchive were NOT limited (F-06, same class).
        data("/processes"),
        data("/admin"),
        // ---------------- F-06: routes that had no bucket at all ----------------
        data("/process-submissions"),
        data("/presets"),
        data("/messages"),
        data("/timer-jobs"),
        data("/message-subscriptions")
    );

    /**
     * Routes that are deliberately NOT rate-limited, with the reason. They are
     * documented here so a future change does not "helpfully" add them — and
     * so the guard test can hold every NEW route to a decision: a route that is
     * neither in the matrix nor in this list fails the guard test.
     */
    private static final Map<String, String> EXCLUDED = Map.of(
        "/auth/verify",
        "nginx auth_request target — nginx calls it for EVERY protected request; "
            + "limiting it would throttle the whole application, not the client",
        "/auth/me",
        "read of the caller's own profile; the pre-matrix registration deliberately "
            + "left it out of every bucket (Api4DataEndpointCoverageTest)",
        "/error",
        "Spring Boot's error dispatch target (BasicErrorController) — a framework route, "
            + "not a business endpoint; the filter does not run on an ERROR dispatch anyway",
        "/v3/api-docs",
        "springdoc OpenAPI document — a GET route outside the WO's scope (the guard "
            + "criterion covers the mutating routes). Recorded as a finding: the schema "
            + "is rendered per request and the route is not limited",
        "/v3/api-docs.yaml",
        "springdoc OpenAPI document in YAML — same as /v3/api-docs"
    );

    /**
     * An exact-path route. The route path is ALWAYS one of the servlet URL
     * patterns: a route without a registration is dead code (the filter never
     * runs for it) — and MockMvc honours the registration patterns, so a
     * missing pattern is visible as a missing limit, not as a silent no-op.
     */
    private static Route exact(Bucket bucket, String path, String method, String... extraPatterns) {
        List<String> patterns = new ArrayList<>();
        patterns.add(path);
        patterns.addAll(List.of(extraPatterns));
        return new Route(bucket, Match.EXACT, path, Set.of(method), List.copyOf(patterns));
    }

    private static Route data(String prefix) {
        return new Route(Bucket.DATA, Match.PREFIX, prefix, DATA_METHODS, List.of(prefix, prefix + "/*"));
    }

    /**
     * @return the bucket the request is charged to, or {@code null} when the
     *         route is not rate-limited at all.
     */
    public static Bucket resolve(String method, String path) {
        if (method == null || path == null) {
            return null;
        }
        String m = method.toUpperCase(Locale.ROOT);
        for (Route route : ROUTES) {
            if (route.matches(m, path)) {
                return route.bucket();
            }
        }
        return null;
    }

    /**
     * The servlet URL patterns the filter must be registered for. Derived from
     * the matrix, so a route can never be "guarded but not registered" (the
     * failure mode F-06 was built on: the guard without a registration is
     * dead code).
     */
    public static List<String> registrationPatterns() {
        Set<String> patterns = new LinkedHashSet<>();
        for (Route route : ROUTES) {
            patterns.addAll(route.urlPatterns());
        }
        return List.copyOf(patterns);
    }

    /** The matrix itself — read-only, for the guard test and for documentation. */
    public static List<Route> routes() {
        return ROUTES;
    }

    /** The deliberately unlimited routes, with the reason in the javadoc above. */
    public static Map<String, String> excludedRoutes() {
        return EXCLUDED;
    }
}
