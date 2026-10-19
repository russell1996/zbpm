package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.rest.security.RateLimitFilter;
import com.zorrodev.bpm.rest.security.RateLimitRoutes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-AUDIT-13 (F-06): the guard test — "a new route can no longer be forgotten".
 *
 * <p>The audit's root cause was a hand-maintained list of routes that had
 * drifted: {@code RateLimitFilterConfig} named one set of URL patterns and
 * {@code RateLimitFilter.isDataEndpoint} another, and half of the mutating
 * surface was in NEITHER. The fix is the {@link RateLimitRoutes} matrix — one
 * list that produces both the registration and the bucket resolution. This
 * test is the mechanical guard that keeps the matrix honest:
 *
 * <ol>
 *   <li>every MUTATING route of the RUNNING application (read from
 *       {@code RequestMappingHandlerMapping}, not from a hand-written list)
 *       is covered by a bucket in the matrix;</li>
 *   <li>every route of the running application is covered by the servlet URL
 *       patterns the filter is actually registered with — a matrix line
 *       without a registration is dead code (the guard would never run);</li>
 *   <li>the exact-path buckets are not shadowed by a prefix line (e.g.
 *       {@code PUT /me/password} must reach the me-password bucket and not
 *       the generic {@code /me} data prefix);</li>
 *   <li>the deliberately unlimited routes stay unlimited (the nginx
 *       auth_request target {@code /auth/verify} must never be throttled);</li>
 *   <li>every matrix line's URL patterns are really registered.</li>
 * </ol>
 *
 * <p>Why the registration is checked against the pattern list AND by live
 * requests: a live 429 proves that the filter RAN for that path (MockMvc
 * honours the {@code FilterRegistrationBean} URL patterns — a route whose
 * pattern is missing is simply not filtered, it answers with the
 * application's own status), while the pattern list proves that every matrix
 * line really is registered. The two halves check different failure modes:
 * the list can hold a pattern the container does not apply, and a live
 * request can pass for a route the matrix does not know about.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
    "zorrobpm.security.rate-limit.enabled=true",
    "zorrobpm.security.rate-limit.data-capacity=5",
    "zorrobpm.security.rate-limit.data-window-seconds=3600",
    "zorrobpm.security.rate-limit.auth-link-capacity=5",
    "zorrobpm.security.rate-limit.auth-link-window-seconds=3600"
})
class Audit13RateLimitRouteMatrixIT {

    /** A live route of the running application. */
    private record LiveRoute(RequestMethod method, String template, String concretePath) {
        @Override
        public String toString() {
            return method + " " + template;
        }
    }

    @Autowired
    private ApplicationContext ctx;

    /**
     * The MVC mapping bean (not the actuator one — the context has two
     * RequestMappingHandlerMapping beans).
     */
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    // ==================== 1. every mutating live route has a bucket ====================

    @Test
    void everyMutatingLiveRoute_isCoveredByTheMatrix() {
        List<String> uncovered = new ArrayList<>();
        for (LiveRoute route : liveRoutes()) {
            if (!isMutating(route.method())) {
                continue;
            }
            if (RateLimitRoutes.resolve(route.method().name(), route.concretePath()) == null
                && !RateLimitRoutes.excludedRoutes().containsKey(route.template())) {
                uncovered.add(route.toString());
            }
        }
        assertThat(uncovered)
            .as("mutating routes of the running application without a rate-limit bucket")
            .isEmpty();
    }

    // ==================== 2. every live route is decided: matrix or explicit exclusion ==========

    @Test
    void everyLiveRoute_isEitherCoveredByTheMatrixOrExplicitlyExcluded() {
        List<String> undecided = new ArrayList<>();
        for (LiveRoute route : liveRoutes()) {
            if (RateLimitRoutes.resolve(route.method().name(), route.concretePath()) != null) {
                continue;
            }
            if (RateLimitRoutes.excludedRoutes().containsKey(route.template())) {
                continue;
            }
            undecided.add(route.toString());
        }
        assertThat(undecided)
            .as("routes that neither the matrix nor the exclusion list decides about — "
                + "a new route must be either limited or excluded WITH a reason")
            .isEmpty();
    }

    // ==================== 3. every covered route is covered by the registration ====================

    @Test
    void everyRouteTheMatrixCovers_isCoveredByTheFilterRegistration() {
        List<String> patterns = registeredPatterns();
        List<String> unregistered = new ArrayList<>();
        for (LiveRoute route : liveRoutes()) {
            if (RateLimitRoutes.resolve(route.method().name(), route.concretePath()) == null) {
                continue;
            }
            if (!matchesAnyPattern(patterns, route.concretePath())) {
                unregistered.add(route.toString());
            }
        }
        assertThat(unregistered)
            .as("routes the filter is not registered for — the guard would be dead code there")
            .isEmpty();
    }

    // ==================== 4. the exact buckets are not shadowed by a prefix ====================

    @Test
    void exactPathBuckets_areNotShadowedByAPrefixRoute() {
        for (RateLimitRoutes.Route route : RateLimitRoutes.routes()) {
            if (route.match() != RateLimitRoutes.Match.EXACT) {
                continue;
            }
            for (String method : route.methods()) {
                assertThat(RateLimitRoutes.resolve(method, route.value()))
                    .as("%s %s must reach its own bucket", method, route.value())
                    .isEqualTo(route.bucket());
            }
        }
    }

    // ==================== 5. the documented exclusions stay unlimited ====================

    @Test
    void documentedExclusions_stayUnlimited() {
        for (String path : RateLimitRoutes.excludedRoutes().keySet()) {
            assertThat(RateLimitRoutes.resolve("GET", path))
                .as("%s must stay outside every bucket (see RateLimitRoutes.excludedRoutes)", path)
                .isNull();
        }
    }

    // ==================== 6. no dead matrix line ====================

    @Test
    void everyMatrixLine_hasItsPatternsRegistered() {
        List<String> patterns = registeredPatterns();
        for (RateLimitRoutes.Route route : RateLimitRoutes.routes()) {
            for (String pattern : route.urlPatterns()) {
                assertThat(patterns)
                    .as("matrix line %s %s declares the pattern %s, but it is not registered",
                        route.bucket(), route.value(), pattern)
                    .contains(pattern);
            }
        }
    }

    // ==================== helpers ====================

    private List<String> registeredPatterns() {
        List<FilterRegistrationBean> registrations = ctx.getBeansOfType(FilterRegistrationBean.class)
            .values().stream()
            .filter(b -> b.getUrlPatterns().contains("/auth/login"))
            .toList();
        assertThat(registrations).as("the rate-limit filter registration bean").hasSize(1);
        return List.copyOf(registrations.get(0).getUrlPatterns());
    }

    /**
     * Servlet path-pattern semantics for the patterns this project uses:
     * an exact path, or a "/prefix/*" pattern that matches the prefix itself
     * and everything below it (servlet spec 12.2).
     */
    private static boolean matchesAnyPattern(List<String> patterns, String path) {
        for (String pattern : patterns) {
            if (pattern.equals(path)) {
                return true;
            }
            if (pattern.endsWith("/*")) {
                String prefix = pattern.substring(0, pattern.length() - 2);
                if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isMutating(RequestMethod method) {
        return method == RequestMethod.POST
            || method == RequestMethod.PUT
            || method == RequestMethod.PATCH
            || method == RequestMethod.DELETE;
    }

    /** The live route table of the running application. */
    private List<LiveRoute> liveRoutes() {
        List<LiveRoute> routes = new ArrayList<>();
        handlerMapping.getHandlerMethods().forEach((RequestMappingInfo info, Object handler) -> {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            Set<RequestMethod> effective = methods.isEmpty() ? allMethods() : methods;
            for (String pattern : info.getPatternValues()) {
                for (RequestMethod method : effective) {
                    routes.add(new LiveRoute(method, pattern, concrete(pattern)));
                }
            }
        });
        return routes;
    }

    /** Replaces the path variables with a dummy value so the path can be matched. */
    private static String concrete(String pattern) {
        return pattern.replaceAll("\\{[^/}]*}", "x");
    }

    private static Set<RequestMethod> allMethods() {
        return Set.of(RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH,
            RequestMethod.DELETE, RequestMethod.HEAD, RequestMethod.OPTIONS);
    }
}
