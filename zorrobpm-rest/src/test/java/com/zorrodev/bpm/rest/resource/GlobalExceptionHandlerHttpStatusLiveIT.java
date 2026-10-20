package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-AUDIT-14 (F-17), end-to-end half: the error-envelope {@code code} must
 * be right on REAL requests, through the real filter chain (JwtAuthFilter →
 * CsrfFilter → RateLimitFilter → DispatcherServlet → GlobalExceptionHandler),
 * not only in a table of handler methods.
 *
 * <p>Each case is a real production path that throws
 * {@code ResponseStatusException} with the status under test:
 * <ul>
 *   <li><b>401</b> — {@code POST /auth/login} with an unknown user
 *       ({@code AuthResource} throws {@code ResponseStatusException(UNAUTHORIZED)});
 *       the path is public, so the request really reaches the controller.</li>
 *   <li><b>405</b> — {@code POST /events} with a valid token: {@code /events}
 *       is GET-only, so the dispatcher raises
 *       {@code HttpRequestMethodNotSupportedException}.</li>
 *   <li><b>415</b> — {@code POST /auth/login} with {@code Content-Type:
 *       application/xml}: the converter lookup fails and the dispatcher raises
 *       {@code HttpMediaTypeNotSupportedException}.</li>
 *   <li><b>429</b> — {@code GET /events/stream} with the SSE client cap at 0:
 *       {@code SseSessionRegistry.add} throws
 *       {@code ResponseStatusException(TOO_MANY_REQUESTS)}.</li>
 * </ul>
 *
 * <p>Before the fix all four answered {@code INTERNAL_ERROR} (415 answered
 * with no envelope at all — Spring's own error body), so a client could not
 * tell an auth refusal from a server crash.
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GlobalExceptionHandlerHttpStatusLiveIT {

    @Autowired MockMvc mockMvc;
    @Autowired SseEventStreamService sseEventStreamService;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String adminToken;
    private Integer savedMaxClients;

    @BeforeAll
    void login() throws Exception {
        adminToken = loginAndGetToken("admin", "admin");
    }

    @AfterEach
    void restoreSseCap() {
        if (savedMaxClients != null) {
            ReflectionTestUtils.setField(sseEventStreamService, "maxClients", savedMaxClients);
            savedMaxClients = null;
        }
    }

    @Test
    void unauthorized_loginWithUnknownUser_answers401WithUnauthorizedCode() throws Exception {
        mockMvc.perform(post("/auth/login")
                .content("{\"username\":\"no-such-user-for-f17\",\"password\":\"whatever\"}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code", is("UNAUTHORIZED")))
            .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void methodNotSupported_postOnGetOnlyEndpoint_answers405WithMethodNotAllowedCode() throws Exception {
        mockMvc.perform(post("/events")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(jsonPath("$.code", is("METHOD_NOT_ALLOWED")));
    }

    @Test
    void unsupportedMediaType_wrongContentType_answers415WithUnsupportedMediaTypeCode() throws Exception {
        mockMvc.perform(post("/auth/login")
                .content("{\"username\":\"admin\",\"password\":\"admin\"}")
                .contentType(MediaType.APPLICATION_XML))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(jsonPath("$.code", is("UNSUPPORTED_MEDIA_TYPE")));
    }

    @Test
    void tooManyRequests_sseCapExceeded_answers429WithRateLimitedCode() throws Exception {
        savedMaxClients = (Integer) ReflectionTestUtils.getField(sseEventStreamService, "maxClients");
        ReflectionTestUtils.setField(sseEventStreamService, "maxClients", 0);

        mockMvc.perform(get("/events/stream")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code", is("RATE_LIMITED")));
    }

    private String loginAndGetToken(String username, String password) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        MvcResult result = mockMvc.perform(post("/auth/login")
                .header("X-Auth-Transport", "bearer")
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class).getToken();
    }
}
