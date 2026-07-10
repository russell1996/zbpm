package com.zorrodev.bpm.rest.security;

import com.zorrodev.bpm.engine.security.TokenService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
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
    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        tokenService = mock(TokenService.class);
        filter = new JwtAuthFilter(tokenService);
        filter.setRequireApiAuth(true);
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
}
