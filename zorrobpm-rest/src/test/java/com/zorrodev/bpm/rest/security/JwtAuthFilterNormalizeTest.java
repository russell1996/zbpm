package com.zorrodev.bpm.rest.security;

import com.zorrodev.bpm.engine.repository.ApiKeyGrantRepository;
import com.zorrodev.bpm.engine.repository.ApiKeyRepository;
import com.zorrodev.bpm.engine.security.TokenService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WO-SEC-10: Path normalization in JwtAuthFilter to prevent auth bypass.
 *
 * Criteria:
 *  #1: /users;x=1 without token → 401 (matrix param bypass)
 *  #2: //users without token → 401 (double-slash bypass)
 *  #3: /users;x=1 with non-ADMIN token → 403 (ADMIN gate still works)
 *  #4: //process-instances without token → 401 (data API bypass)
 *  #5: Normal paths work as before (existing tests)
 *  #6: /auth/login still open (existing tests)
 *  #7: proof-of-failure — test #1 on current code → passes without 401 (RED)
 */
class JwtAuthFilterNormalizeTest {

    private TokenService tokenService;
    private com.zorrodev.bpm.engine.security.UiUserLookupService userLookup;
    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        tokenService = mock(TokenService.class);
        userLookup = mock(com.zorrodev.bpm.engine.security.UiUserLookupService.class);
        var env = mock(org.springframework.core.env.Environment.class);
        filter = new JwtAuthFilter(tokenService, mock(ApiKeyRepository.class), mock(ApiKeyGrantRepository.class), userLookup, mock(com.zorrodev.bpm.engine.security.AuthorizationService.class), env);
        filter.setRequireApiAuth(true);
        // WO-SEC-63: stub a default securityState for any userId; most tests use USER role,
        // which matches this default and exercises the path-based guard (403).
        when(userLookup.securityState(any())).thenReturn(java.util.Optional.of(
            new com.zorrodev.bpm.engine.security.UiUserLookupService.UserSecurityState(
                null, "anyuser", "USER", true, 0, false)));
    }

    private int doFilter(String path) throws Exception {
        return doFilter(path, null);
    }

    private int doFilter(String path, String bearerToken) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        if (bearerToken != null) {
            request.addHeader("Authorization", "Bearer " + bearerToken);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilterInternal(request, response, chain);
        return response.getStatus();
    }

    // --- Criterion #1: /users;x=1 without token → 401 ---

    @Test
    void criterion1_usersWithMatrixParam_noToken_returns401() throws Exception {
        assertThat(doFilter("/users;x=1")).isEqualTo(401);
    }

    // --- Criterion #2: //users without token → 401 ---

    @Test
    void criterion2_doubleSlashUsers_noToken_returns401() throws Exception {
        assertThat(doFilter("//users")).isEqualTo(401);
    }

    // --- Criterion #3: /users;x=1 with non-ADMIN → 403 ---

    @Test
    void criterion3_usersWithMatrixParam_nonAdmin_returns403() throws Exception {
        TokenService.Claims claims = mock(TokenService.Claims.class);
        when(claims.role()).thenReturn("USER");
        when(tokenService.verify("user-token")).thenReturn(claims);
        assertThat(doFilter("/users;x=1", "user-token")).isEqualTo(403);
    }

    // --- Criterion #4: //process-instances without token → 401 ---

    @Test
    void criterion4_doubleSlashProcessInstances_noToken_returns401() throws Exception {
        assertThat(doFilter("//process-instances")).isEqualTo(401);
    }

    // --- Criterion #5: Normal paths still work ---

    @Test
    void criterion5_normalUsersPath_noToken_returns401() throws Exception {
        assertThat(doFilter("/users")).isEqualTo(401);
    }

    @Test
    void criterion5_normalProcessInstances_noToken_returns401() throws Exception {
        assertThat(doFilter("/process-instances")).isEqualTo(401);
    }

    @Test
    void criterion5_trailingSlashUsers_noToken_returns401() throws Exception {
        assertThat(doFilter("/users/")).isEqualTo(401);
    }

    // --- Proof-of-failure #7: /users;x=1 without token on current code → NOT 401 (RED) ---

    @Test
    void criterion7_proofOfFailure_usersMatrixParam_bypassesAuth() throws Exception {
        int status = doFilter("/users;x=1");
        // On fixed code: 401. On unfixed code: 200 (bypass).
        assertThat(status).isEqualTo(401);
    }

    // ==================== WO-SEC-15: %-encoding bypass tests ====================

    /** Proof-of-failure: /%75sers (=%/users) without token should be 401 after fix. */
    @Test
    void sec15_proofOfFailure_percentEncodedUsers_noToken_returns401() throws Exception {
        // On current code (before fix): PathNormalizer does NOT decode %,
        // so /%75sers stays /%75sers → not matched as protected → 200 (bypass) = RED.
        // After fix: /%75sers → /users → protected → 401 = GREEN.
        assertThat(doFilter("/%75sers")).isEqualTo(401);
    }

    /** Encoded slash in path should NOT bypass auth. */
    @Test
    void sec15_encodedSlash_bypassesAuth() throws Exception {
        // /%2fusers → //users after decode → /users after collapse → protected → 401.
        assertThat(doFilter("/%2fusers")).isEqualTo(401);
    }

    /** Mixed: /users%3Bx=1 (encoded semicolon = matrix). */
    @Test
    void sec15_encodedSemicolon_noToken_returns401() throws Exception {
        // /users%3Bx=1 → /users;x=1 after decode → protected → 401.
        assertThat(doFilter("/users%3Bx=1")).isEqualTo(401);
    }

    /** /process-instances via encoded path. */
    @Test
    void sec15_encodedProcessInstances_noToken_returns401() throws Exception {
        // /%70rocess-instances → /process-instances → protected → 401.
        assertThat(doFilter("/%70rocess-instances")).isEqualTo(401);
    }

    /** /auth/login with encoding still open. */
    @Test
    void sec15_encodedAuthLogin_stillOpen() throws Exception {
        // /%61uth/login → /auth/login → NOT protected → 200 (passes through).
        assertThat(doFilter("/%61uth/login")).isEqualTo(200);
    }

    /** Normal /users still works (regression check). */
    @Test
    void sec15_normalUsersStillProtected() throws Exception {
        assertThat(doFilter("/users")).isEqualTo(401);
    }

    // ==================== WO-BE-4: path traversal bypass tests ====================

    // --- Criterion #1: /auth/../users/{id} without SUPER_ADMIN → 403 ---

    @Test
    void be4_traversalUsersPath_nonAdmin_returns403() throws Exception {
        // /auth/../users/123 → /users/123 after normalize → SUPER_ADMIN guard
        TokenService.Claims claims = mock(TokenService.Claims.class);
        when(claims.role()).thenReturn("USER");
        when(tokenService.verify("user-token")).thenReturn(claims);
        assertThat(doFilter("/auth/../users/123", "user-token")).isEqualTo(403);
    }

    @Test
    void be4_traversalUsersPath_noToken_returns401() throws Exception {
        // /auth/../users/123 → /users/123 after normalize → protected → needs auth
        assertThat(doFilter("/auth/../users/123")).isEqualTo(401);
    }

    @Test
    void be4_traversalUsersPath_superAdmin_returns200() throws Exception {
        // SUPER_ADMIN can access /users — securityState must match both role and userId
        UUID adminId = UUID.randomUUID();
        TokenService.Claims claims = mock(TokenService.Claims.class);
        when(claims.userId()).thenReturn(adminId);
        when(claims.role()).thenReturn("SUPER_ADMIN");
        when(claims.tokenVersion()).thenReturn(0);
        when(tokenService.verify("admin-token")).thenReturn(claims);
        when(userLookup.securityState(adminId)).thenReturn(java.util.Optional.of(
            new com.zorrodev.bpm.engine.security.UiUserLookupService.UserSecurityState(
                adminId, "admin", "SUPER_ADMIN", true, 0, false)));
        int status = doFilter("/auth/../users/123", "admin-token");
        // Filter passes through → 200 (no downstream handler in test), which means
        // the filter did NOT reject it
        assertThat(status).isEqualTo(200);
    }

    // --- Criterion #2: Legitimate paths work as before (regression) ---

    @Test
    void be4_normalUsersPath_nonAdmin_returns403() throws Exception {
        // Regression: plain /users/123 still guarded
        TokenService.Claims claims = mock(TokenService.Claims.class);
        when(claims.role()).thenReturn("USER");
        when(tokenService.verify("user-token")).thenReturn(claims);
        assertThat(doFilter("/users/123", "user-token")).isEqualTo(403);
    }

    @Test
    void be4_authLogin_stillOpen() throws Exception {
        assertThat(doFilter("/auth/login")).isEqualTo(200);
    }

    @Test
    void be4_authRefresh_stillOpen() throws Exception {
        assertThat(doFilter("/auth/refresh")).isEqualTo(200);
    }

    // --- Criterion #3: Other traversal vectors closed ---

    @Test
    void be4_percentEncodedDoubleDot_usersPath_nonAdmin_returns403() throws Exception {
        // /%2e%2e/users/123 → /../users/123 after decode → /users/123 after resolve
        TokenService.Claims claims = mock(TokenService.Claims.class);
        when(claims.role()).thenReturn("USER");
        when(tokenService.verify("user-token")).thenReturn(claims);
        assertThat(doFilter("/%2e%2e/users/123", "user-token")).isEqualTo(403);
    }

    @Test
    void be4_doubleSlashDoubleDot_usersPath_nonAdmin_returns403() throws Exception {
        TokenService.Claims claims = mock(TokenService.Claims.class);
        when(claims.role()).thenReturn("USER");
        when(tokenService.verify("user-token")).thenReturn(claims);
        assertThat(doFilter("//..//users/123", "user-token")).isEqualTo(403);
    }

    @Test
    void be4_matrixParamDoubleDot_usersPath_nonAdmin_returns403() throws Exception {
        // /auth/..;/users/123 → matrix strip: /auth/../users/123 → /users/123
        TokenService.Claims claims = mock(TokenService.Claims.class);
        when(claims.role()).thenReturn("USER");
        when(tokenService.verify("user-token")).thenReturn(claims);
        assertThat(doFilter("/auth/..;/users/123", "user-token")).isEqualTo(403);
    }

    @Test
    void be4_cyclicDoubleDot_usersPath_nonAdmin_returns403() throws Exception {
        // /users/../users/123 → /users/123
        TokenService.Claims claims = mock(TokenService.Claims.class);
        when(claims.role()).thenReturn("USER");
        when(tokenService.verify("user-token")).thenReturn(claims);
        assertThat(doFilter("/users/../users/123", "user-token")).isEqualTo(403);
    }

    @Test
    void be4_doubleEncodedDoubleDot_usersPath_nonAdmin_returns403() throws Exception {
        TokenService.Claims claims = mock(TokenService.Claims.class);
        when(claims.role()).thenReturn("USER");
        when(tokenService.verify("user-token")).thenReturn(claims);
        assertThat(doFilter("/%2e%2e/%2e%2e/users", "user-token")).isEqualTo(403);
    }

    @Test
    void be4_traversalAboveRoot_usersPath_nonAdmin_returns403() throws Exception {
        // /a/../../users/123 → /users/123
        TokenService.Claims claims = mock(TokenService.Claims.class);
        when(claims.role()).thenReturn("USER");
        when(tokenService.verify("user-token")).thenReturn(claims);
        assertThat(doFilter("/a/../../users/123", "user-token")).isEqualTo(403);
    }

    /** Proof-of-failure: RED — without fix, traversal bypasses SUPER_ADMIN guard. */
    @Test
    void be4_proofOfFailure_traversal_bypassesSuperAdmin() throws Exception {
        // This test isolates the normalize behavior. On unfixed code (without
        // resolveDotSegments), /auth/../users/123 stays as-is → isUsersPath()=false
        // → SUPER_ADMIN guard skipped → regular USER passes through → 200.
        // On fixed code: /auth/../users/123 → /users/123 → isUsersPath()=true → 403.
        TokenService.Claims claims = mock(TokenService.Claims.class);
        when(claims.role()).thenReturn("USER");
        when(tokenService.verify("user-token")).thenReturn(claims);
        assertThat(doFilter("/auth/../users/123", "user-token")).isEqualTo(403);
    }
}
