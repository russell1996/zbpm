package com.zorrodev.bpm.rest.security;

import com.zorrodev.bpm.engine.repository.ApiKeyGrantRepository;
import com.zorrodev.bpm.engine.repository.ApiKeyRepository;
import com.zorrodev.bpm.engine.security.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for JwtAuthFilter: verifies isProtected logic without Spring context.
 * Criterion #7: with require-api-auth=false, data API endpoints pass through.
 */
class JwtAuthFilterTest {

    private TokenService tokenService;
    private com.zorrodev.bpm.engine.security.UiUserLookupService userLookup;
    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        tokenService = mock(TokenService.class);
        ApiKeyRepository apiKeyRepo = mock(ApiKeyRepository.class);
        ApiKeyGrantRepository apiKeyGrantRepo = mock(ApiKeyGrantRepository.class);
        userLookup = mock(com.zorrodev.bpm.engine.security.UiUserLookupService.class);
        var authz = mock(com.zorrodev.bpm.engine.security.AuthorizationService.class);
        var env = mock(org.springframework.core.env.Environment.class);
        filter = new JwtAuthFilter(tokenService, apiKeyRepo, apiKeyGrantRepo, userLookup, authz, env);
    }

    private void setRequireApiAuth(boolean value) {
        filter.setRequireApiAuth(value);
    }

    @Test
    void dataApiPath_protectedByDefault() throws Exception {
        setRequireApiAuth(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/process-instances");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void dataApiPath_flagDisabled_passesThrough() throws Exception {
        setRequireApiAuth(false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/process-instances");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        // Request passes through to chain (not blocked)
        verify(chain).doFilter(request, response);
    }

    @Test
    void authLogin_neverProtected() throws Exception {
        setRequireApiAuth(true);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void authMe_alwaysProtected() throws Exception {
        setRequireApiAuth(false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/me");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void userTasks_protectedByDefault() throws Exception {
        setRequireApiAuth(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/user-tasks");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void validToken_passesThrough_withClaimsAttribute() throws Exception {
        setRequireApiAuth(true);
        TokenService.Claims claims = mock(TokenService.Claims.class);
        UUID userId = UUID.randomUUID();
        when(claims.userId()).thenReturn(userId);
        when(claims.role()).thenReturn("USER");
        when(claims.tokenVersion()).thenReturn(7);
        when(tokenService.verify("good-token")).thenReturn(claims);
        // WO-SEC-63: filter resolves the live user-state on every JWT request; a matching
        // active user with SAME role + tokenVersion must pass through.
        when(userLookup.securityState(userId)).thenReturn(java.util.Optional.of(
            new com.zorrodev.bpm.engine.security.UiUserLookupService.UserSecurityState(
                userId, "alice", "USER", true, 7, false)));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/process-instances");
        request.addHeader("Authorization", "Bearer good-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(request.getAttribute("authClaims")).isSameAs(claims);
    }

    @Test
    void usersPath_nonAdminRole_returns403() throws Exception {
        TokenService.Claims claims = mock(TokenService.Claims.class);
        UUID userId = UUID.randomUUID();
        when(claims.userId()).thenReturn(userId);
        when(claims.role()).thenReturn("USER");
        when(claims.tokenVersion()).thenReturn(0);
        when(tokenService.verify("user-token")).thenReturn(claims);
        // Active user in DB, role matches the claim → the "/users" admin path check
        // (not the filter itself) decides the 403.
        when(userLookup.securityState(userId)).thenReturn(java.util.Optional.of(
            new com.zorrodev.bpm.engine.security.UiUserLookupService.UserSecurityState(
                userId, "bob", "USER", true, 0, false)));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/users");
        request.addHeader("Authorization", "Bearer user-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
    }

    // --- WO-SEC-63 (F01): the JWT hot path must invalidate a token as soon as the DB state
    // diverges from the claims — tokenVersion bump (logout/password change), deactivation,
    // or role change must all turn a previously-valid bearer token into 401, not 200.

    @Test
    void tokenVersionMismatch_staleToken_returns401() throws Exception {
        setRequireApiAuth(true);
        TokenService.Claims claims = mock(TokenService.Claims.class);
        UUID userId = UUID.randomUUID();
        when(claims.userId()).thenReturn(userId);
        when(claims.role()).thenReturn("USER");
        // Token was issued BEFORE the user logged out → carries stale version 3.
        when(claims.tokenVersion()).thenReturn(3);
        when(tokenService.verify("stale-token")).thenReturn(claims);
        // Logout incremented the DB version to 4 → mismatch must invalidate.
        when(userLookup.securityState(userId)).thenReturn(java.util.Optional.of(
            new com.zorrodev.bpm.engine.security.UiUserLookupService.UserSecurityState(
                userId, "alice", "USER", true, 4, false)));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/process-instances");
        request.addHeader("Authorization", "Bearer stale-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(request.getAttribute("authClaims")).isNull();
    }

    @Test
    void inactiveUser_token_returns401() throws Exception {
        setRequireApiAuth(true);
        TokenService.Claims claims = mock(TokenService.Claims.class);
        UUID userId = UUID.randomUUID();
        when(claims.userId()).thenReturn(userId);
        when(claims.role()).thenReturn("USER");
        when(claims.tokenVersion()).thenReturn(0);
        when(tokenService.verify("deactivated-token")).thenReturn(claims);
        // Deactivated in DB (version was also bumped by UiUserServiceImpl.update on
        // deactivation, but the active=false alone must already block).
        when(userLookup.securityState(userId)).thenReturn(java.util.Optional.of(
            new com.zorrodev.bpm.engine.security.UiUserLookupService.UserSecurityState(
                userId, "alice", "USER", false, 0, false)));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/process-instances");
        request.addHeader("Authorization", "Bearer deactivated-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(request.getAttribute("authClaims")).isNull();
    }

    @Test
    void roleDemoted_token_returns401() throws Exception {
        // Role demotion (SUPER_ADMIN → USER) must also kill old tokens immediately:
        // JWT path re-checks role from DB like the API-key path always did (F01).
        setRequireApiAuth(true);
        TokenService.Claims claims = mock(TokenService.Claims.class);
        UUID userId = UUID.randomUUID();
        when(claims.userId()).thenReturn(userId);
        // The token still claims SUPER_ADMIN.
        when(claims.role()).thenReturn("SUPER_ADMIN");
        when(claims.tokenVersion()).thenReturn(0);
        when(tokenService.verify("demoted-token")).thenReturn(claims);
        // DB has already demoted the user; version bump from update() covers this too,
        // but the role check is the dedicated F01 guard and must block on its own.
        when(userLookup.securityState(userId)).thenReturn(java.util.Optional.of(
            new com.zorrodev.bpm.engine.security.UiUserLookupService.UserSecurityState(
                userId, "bob", "USER", true, 0, false)));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/process-instances");
        request.addHeader("Authorization", "Bearer demoted-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(request.getAttribute("authClaims")).isNull();
    }

    @Test
    void userStateMissing_token_returns401() throws Exception {
        setRequireApiAuth(true);
        TokenService.Claims claims = mock(TokenService.Claims.class);
        UUID userId = UUID.randomUUID();
        when(claims.userId()).thenReturn(userId);
        when(claims.role()).thenReturn("USER");
        when(claims.tokenVersion()).thenReturn(1);
        when(tokenService.verify("deleted-token")).thenReturn(claims);
        when(userLookup.securityState(userId)).thenReturn(java.util.Optional.empty());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/process-instances");
        request.addHeader("Authorization", "Bearer deleted-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(request.getAttribute("authClaims")).isNull();
    }

    @Test
    void allDataApiPaths_protectedByDefault() throws Exception {
        setRequireApiAuth(true);
        String[] paths = {
            "/process-instances", "/user-tasks", "/variables", "/incidents",
            "/dmn", "/timer-jobs", "/message-subscriptions",
            "/process-definitions", "/service-tasks"
        };

        for (String path : paths) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilterInternal(request, response, chain);

            assertThat(response.getStatus())
                .as("Expected 401 for %s", path)
                .isEqualTo(401);
        }
    }

    @Test
    void api_key_debounce_doesNotSaveTwice() throws Exception {
        // WO-SEC-34: two consecutive requests with same API key → touch called once.
        // WO-SEC-66 (F07): the debounce write is the conditional single-column
        // touchLastUsedAtIfLive — NEVER a full-entity save (a save would merge a
        // stale detached snapshot back over a concurrent revoke).
        UUID apiKeyId = UUID.randomUUID();
        com.zorrodev.bpm.engine.entity.ApiKeyEntity apiKey =
            new com.zorrodev.bpm.engine.entity.ApiKeyEntity();
        apiKey.setId(apiKeyId);
        apiKey.setPrefix("zbpm_sk_");
        apiKey.setRevokedAt(null);
        apiKey.setExpiresAt(null);
        apiKey.setOwnerUserId(UUID.randomUUID());

        ApiKeyRepository apiKeyRepo = mock(ApiKeyRepository.class);
        ApiKeyGrantRepository apiKeyGrantRepo = mock(ApiKeyGrantRepository.class);
        when(apiKeyRepo.findByKeyHash(anyString())).thenReturn(java.util.Optional.of(apiKey));
        when(apiKeyGrantRepo.findByApiKeyId(any())).thenReturn(java.util.List.of());

        var userLookup = mock(com.zorrodev.bpm.engine.security.UiUserLookupService.class);
        when(userLookup.isActive(any())).thenReturn(true);
        var authz = mock(com.zorrodev.bpm.engine.security.AuthorizationService.class);
        when(authz.effectiveGrants(any(), any())).thenReturn(java.util.Map.of());
        var env = mock(org.springframework.core.env.Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"test"});

        JwtAuthFilter f = new JwtAuthFilter(tokenService, apiKeyRepo, apiKeyGrantRepo, userLookup, authz, env);
        f.setRequireApiAuth(true);

        // First request — should save
        MockHttpServletRequest req1 = new MockHttpServletRequest("GET", "/process-instances");
        req1.addHeader("Authorization", "Bearer zbpm_sk_test123");
        MockHttpServletResponse res1 = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        f.doFilterInternal(req1, res1, chain);

        // Second request immediately — should NOT save (debounced)
        MockHttpServletRequest req2 = new MockHttpServletRequest("GET", "/process-instances");
        req2.addHeader("Authorization", "Bearer zbpm_sk_test123");
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        FilterChain chain2 = mock(FilterChain.class);
        f.doFilterInternal(req2, res2, chain2);

        // WO-SEC-66: conditional touch called only once (first request), and the
        // full-entity save is NEVER used on the debounce path — not once.
        verify(apiKeyRepo, org.mockito.Mockito.times(1))
            .touchLastUsedAtIfLive(eq(apiKeyId), any(java.time.Instant.class));
        verify(apiKeyRepo, org.mockito.Mockito.never())
            .save(any(com.zorrodev.bpm.engine.entity.ApiKeyEntity.class));
    }

    // WO-SEC-43: unauthenticated top-level browser navigation (e.g. typing /swagger-ui/index.html
    // directly) redirects to the login page instead of showing a raw 401 Whitelabel error page.

    @Test
    void unauthenticatedBrowserNavigation_redirectsToLogin() throws Exception {
        setRequireApiAuth(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/swagger-ui/index.html");
        request.addHeader("Sec-Fetch-Mode", "navigate");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(302);
        // Carries the original destination so Login.vue can send the user back to Swagger
        // instead of defaulting to the SPA dashboard after a successful login.
        assertThat(response.getRedirectedUrl()).isEqualTo("/ui/login?redirect=%2Fswagger-ui%2Findex.html");
    }

    @Test
    void unauthenticatedBrowserNavigation_preservesQueryString() throws Exception {
        setRequireApiAuth(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v3/api-docs");
        request.setQueryString("group=users");
        request.addHeader("Sec-Fetch-Mode", "navigate");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getRedirectedUrl()).isEqualTo("/ui/login?redirect=%2Fv3%2Fapi-docs%3Fgroup%3Dusers");
    }

    @Test
    void unauthenticatedApiCall_stillReturnsJson401_notRedirected() throws Exception {
        // The SPA's own axios calls (fetch/XHR) never send Sec-Fetch-Mode: navigate - this must
        // keep returning a plain 401 so refreshInterceptor.ts's existing handling isn't broken.
        setRequireApiAuth(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/process-instances");
        request.addHeader("Sec-Fetch-Mode", "cors");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getRedirectedUrl()).isNull();
    }

    @Test
    void unauthenticatedRequest_noSecFetchModeHeader_stillReturnsJson401() throws Exception {
        // Non-browser clients (curl, older browsers) without Sec-Fetch-Mode fall through to the
        // existing behavior - never accidentally redirected.
        setRequireApiAuth(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/swagger-ui/index.html");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getRedirectedUrl()).isNull();
    }

    @Test
    void unauthenticatedNonGetNavigation_stillReturnsJson401() throws Exception {
        // Sec-Fetch-Mode: navigate can also occur for e.g. form POST navigations - only redirect
        // GET (an actual document-load navigation), not other methods.
        setRequireApiAuth(true);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/process-instances");
        request.addHeader("Sec-Fetch-Mode", "navigate");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getRedirectedUrl()).isNull();
    }
}
