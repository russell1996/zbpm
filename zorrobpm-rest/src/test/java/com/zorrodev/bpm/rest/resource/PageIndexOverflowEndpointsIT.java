package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-QW-14 — {@code pageIndex} outside the int offset must not escape as a 500 on the two
 * endpoints that build their {@link org.springframework.data.domain.PageRequest} themselves,
 * bypassing {@code QueryPaginationSupport.clampedPage}.
 *
 * <p>Why these two and nobody else: {@code PageRequest.getOffset()} returns {@code long}, so the
 * product does not overflow in the caller — it overflows inside Spring Data JPA's
 * {@code PageableUtils.getOffsetAsInteger}, which throws
 * {@code InvalidDataAccessApiUsageException: Page offset exceeds Integer.MAX_VALUE}. No
 * {@code @ControllerAdvice} in the project handles it, so plain caller input became a 500, while
 * every endpoint that goes through {@code clampedPage} (the eight query operations) was already
 * fixed by WO-IN-2.
 *
 * <p><b>V11</b>: real HTTP over the full Spring context (MockMvc + live {@code JwtAuthFilter} +
 * real database), because the thing under test is the composition resource → service → repository
 * → Spring Data offset cast. A unit test of the service alone would not go through
 * {@code PageableUtils}, i.e. it could not fail for the production reason.
 *
 * <p><b>P-67 — the assertions are on concrete values that differ between correct and broken</b>,
 * not "something was called":
 * <ul>
 *   <li>broken: the request never produces a response body — Spring Data throws, so the test
 *       ERRORs instead of asserting {@code data == []};
 *   <li>correct: HTTP 200, {@code totalElements} equal to the <i>same</i> count the endpoint
 *       reports at {@code pageIndex=0} (proof the query really ran with the same filters and
 *       returned a real count, not a stub), and {@code data} empty because the window is past the
 *       end.</li>
 * </ul>
 * Mutating the production code back to {@code PageRequest.of(Math.max(0, index), …)} makes all
 * three far-page tests RED and leaves the legal-page tests green — that asymmetry is the proof
 * that they are bound to the clamp and not to the endpoint merely existing.
 *
 * <p><b>Order independence (P-59).</b> The module shares one database across classes in
 * nondeterministic order, so nothing here assumes whether any process definition exists. The
 * legal-page assertions are written as {@code data.size() == min(totalElements, pageSize)} and, for
 * {@code /users}, additionally that the seeded {@code admin} is present — both true whatever other
 * tests deployed. Nothing is deployed or mutated by this class.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PageIndexOverflowEndpointsIT {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String adminToken;

    @BeforeAll
    void login() throws Exception {
        LoginDTO loginDTO = new LoginDTO();
        loginDTO.setUsername("admin");
        loginDTO.setPassword("admin");
        MvcResult result = mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer")
                        .content(mapper.writeValueAsString(loginDTO))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        adminToken = mapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class).getToken();
    }

    // ================= /process-definitions =================

    /**
     * RED before the fix: {@code ProcessDefinitionServiceImpl} clamped only the size, so the index
     * went to Spring Data as 2147483647 and the cast blew up — a 500 out of caller input.
     */
    @Test
    void processDefinitions_indexBeyondIntOffset_isEmptyPage_notServerError() throws Exception {
        long totalAtFirstPage = readTotal("/process-definitions?pageIndex=0&pageSize=10");

        Map<String, Object> far = read("/process-definitions?pageIndex=2147483647&pageSize=10");

        assertThat(totalAtFirstPage)
                .as("sanity: the legal page answered with a real count")
                .isGreaterThanOrEqualTo(0L);
        assertThat(total(far))
                .as("same count as the first page — the query ran, it is not a stubbed empty page")
                .isEqualTo(totalAtFirstPage);
        assertThat(data(far)).as("a window past the end holds no rows").isEmpty();
    }

    /**
     * The clamp must not swallow a legal, merely distant page: {@code pageIndex=1000} was legal
     * before the fix and must stay legal — same shape as the far page, still 200, still the real
     * total. A clamp that collapsed everything to page 0 would fail here while passing the test
     * above, so the two together pin the direction of the guard.
     */
    @Test
    void processDefinitions_distantButLegalIndex_behavesLikeAnyPagePastTheEnd() throws Exception {
        long totalAtFirstPage = readTotal("/process-definitions?pageIndex=0&pageSize=10");

        Map<String, Object> page = read("/process-definitions?pageIndex=1000&pageSize=10");

        assertThat(total(page)).isEqualTo(totalAtFirstPage);
        assertThat(data(page)).isEmpty();
    }

    /** The positive half: at a legal index the endpoint still returns rows when there are any. */
    @Test
    void processDefinitions_legalIndex_stillReturnsRowsUpToPageSize() throws Exception {
        Map<String, Object> first = read("/process-definitions?pageIndex=0&pageSize=10");

        assertThat(data(first).size())
                .as("a first page holds min(totalElements, pageSize) rows")
                .isEqualTo((int) Math.min(total(first), 10L));
    }

    // ================= /users =================

    /**
     * RED before the fix: {@code UiUserServiceImpl} computed
     * {@code Math.max(0, query.getPageIndex())} and handed it straight to {@code PageRequest.of}.
     */
    @Test
    void users_indexBeyondIntOffset_isEmptyPage_notServerError() throws Exception {
        long totalAtFirstPage = readTotal("/users?pageIndex=0&pageSize=10");

        Map<String, Object> far = read("/users?pageIndex=2147483647&pageSize=10");

        assertThat(total(far)).isEqualTo(totalAtFirstPage);
        assertThat(data(far)).isEmpty();
    }

    @Test
    void users_distantButLegalIndex_behavesLikeAnyPagePastTheEnd() throws Exception {
        long totalAtFirstPage = readTotal("/users?pageIndex=0&pageSize=10");

        Map<String, Object> page = read("/users?pageIndex=1000&pageSize=10");

        assertThat(total(page)).isEqualTo(totalAtFirstPage);
        assertThat(data(page)).isEmpty();
    }

    /**
     * Positive half with a concrete expected value: the seeded administrator is always there (the
     * login in {@code @BeforeAll} just used it), so a legal first page is not empty and the clamp
     * demonstrably did not turn {@code /users} into an always-empty listing.
     */
    @Test
    void users_legalIndex_stillReturnsRowsIncludingTheSeededAdmin() throws Exception {
        Map<String, Object> first = read("/users?pageIndex=0&pageSize=200");

        assertThat(usernames(data(first))).contains("admin");
        assertThat(data(first).size()).isEqualTo((int) Math.min(total(first), 200L));
    }

    // ================= helpers =================

    private Map<String, Object> read(String url) throws Exception {
        MvcResult result = mockMvc.perform(get(url).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = mapper.readValue(result.getResponse().getContentAsString(), Map.class);
        return body;
    }

    private long readTotal(String url) throws Exception {
        return total(read(url));
    }

    @SuppressWarnings("unchecked")
    private static long total(Map<String, Object> body) {
        return ((Number) body.get("totalElements")).longValue();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> data(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("data");
    }

    private static List<String> usernames(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> (String) r.get("username")).toList();
    }
}