package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.LoginDTO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-REL-70 (критерии 1, 2): SSE-ответ за буферизующим reverse proxy.
 *
 * <p>Прод-симптом: внешний edge-nginx с дефолтным {@code proxy_buffering on}
 * держал {@code GET /events/stream} в «pending» — бэкенд не слал
 * {@code X-Accel-Buffering: no}, и handshake/первые байты не уходили клиенту,
 * пока не заполнится буфер (heartbeat-тик 15с — крошечный). Фикс: заголовки
 * anti-buffering на успехе + немедленный {@code :connected} (см.
 * {@link SseImmediateHelloTest}).
 *
 * <p>Full-context (V11): реальная цепочка фильтров + реальный контроллер.
 * POF-мутация: убрать {@code setHeader} из {@code SseEventStreamController} —
 * {@code stream_setsNoBufferingHeaders} КРАСНЫЙ
 * ({@code expected:<no> but was:<null>}).
 */
@SpringBootTest(classes = TestMain.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("test")
class SseProxyBufferingHeadersTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private SseEventStreamService sseEventStreamService;

    private String adminToken;

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin", "admin");
    }

    @BeforeEach
    void clearListeners() {
        sseEventStreamService.clearEventListeners();
    }

    @SuppressWarnings("unchecked")
    private String login(String username, String password) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        MvcResult result = mockMvc.perform(loginPost("/auth/login", dto))
            .andExpect(status().isOk())
            .andReturn();
        Map<String, Object> body = new com.fasterxml.jackson.databind.ObjectMapper()
            .readValue(result.getResponse().getContentAsString(), Map.class);
        return (String) body.get("token");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder loginPost(
            String url, LoginDTO dto) throws Exception {
        return post(url).header("X-Auth-Transport", "bearer")
            .content(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(dto))
            .contentType(MediaType.APPLICATION_JSON);
    }

    /**
     * Критерий 2: успех несёт anti-buffering заголовки — nginx с дефолтным
     * {@code proxy_buffering on} выключает буферизацию ЭТОГО ответа по
     * {@code X-Accel-Buffering: no} от апстрима.
     */
    @Test
    void stream_setsNoBufferingHeaders() throws Exception {
        mockMvc.perform(get("/events/stream")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "text/event-stream"))
            .andExpect(header().string("X-Accel-Buffering", "no"))
            .andExpect(header().string("Cache-Control", "no-cache, no-transform"));
    }

    /**
     * Критерий 2: заголовки стоят на всех ветках успеха (с фильтрами и
     * {@code Last-Event-ID} — catchup-путь их не теряет).
     */
    @Test
    void stream_setsNoBufferingHeaders_withFiltersAndCatchup() throws Exception {
        mockMvc.perform(get("/events/stream")
                .header("Authorization", "Bearer " + adminToken)
                .header("Last-Event-ID", "100")
                .param("type", "process-instance.started")
                .param("processDefinitionKey", "testProcess"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Accel-Buffering", "no"))
            .andExpect(header().string("Cache-Control", "no-cache, no-transform"));
    }

    /**
     * Критерий 2: ошибочный ответ (401 от JwtAuthFilter — идёт МИМО
     * контроллера) НЕ несёт SSE-заголовков и своей формы не меняет.
     * 429 идёт тем же обходным путём (RateLimitFilter коротит до
     * контроллера — форма закреплена
     * {@code RateLimitFilterSseBucketTest.sseBucket_overflow_returns429},
     * контроллер в этом пути не участвует вовсе).
     */
    @Test
    void unauthenticated_hasNoSseBufferingHeaders() throws Exception {
        mockMvc.perform(get("/events/stream"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().doesNotExist("X-Accel-Buffering"))
            .andExpect(header().doesNotExist("Cache-Control"));
    }
}
