package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.rest.security.RateLimitFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * WO-SEC-86, критерий 2 (регресс SEC-4/SEC-44/SEC-84): подделанный {@code X-Real-IP}
 * от НЕдоверенного пира игнорируется — ключ бакета остаётся реальным TCP-пиром.
 *
 * <p>Здесь {@code trusted-proxies} НЕ задан (secure-дефолт: пусто = никому не
 * доверять); сокет-пир MockMvc — 127.0.0.1, каждый запрос несёт «новый» X-Real-IP.
 * Лимит всё равно срабатывает по общему сокет-пиру — заголовок не даёт свежего
 * бакета. V11: полный контекст + реальная цепочка фильтров.
 *
 * <p>POF-мутация: доверять X-Real-IP безусловно (убрать проверку
 * {@code isTrustedProxy}) — оба теста КРАСНЫЕ (6-й/4-й запросы дают 200 вместо 429:
 * каждый подделанный IP уходил бы в свежий бакет, классический обход SEC-4).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
    "zorrobpm.security.rate-limit.enabled=true",
    "zorrobpm.security.rate-limit.capacity=5",
    "zorrobpm.security.rate-limit.window-seconds=3600",
    "zorrobpm.security.rate-limit.account-capacity=100",
    "zorrobpm.security.rate-limit.data-capacity=3",
    "zorrobpm.security.rate-limit.data-window-seconds=3600"
    // trusted-proxies намеренно НЕ задан — secure-дефолт WO-SEC-86.
})
class Sec86UntrustedPeerIgnoresRealIpIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RateLimitFilter rateLimitFilter;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        rateLimitFilter.reset();
    }

    @AfterEach
    void tearDown() {
        rateLimitFilter.reset();
    }

    @Test
    void login_spoofedRealIpFromUntrustedPeer_stillLimited() throws Exception {
        // 5 запросов с РАЗНЫМИ подделанными X-Real-IP — делят один настоящий
        // бакет сокет-пира; все доходят до аутентификации (401, не 429).
        for (int i = 0; i < 5; i++) {
            int status = login("sec86-spoof-" + UUID.randomUUID().toString().substring(0, 8),
                "WrongPass123!", "10.99.0." + (i + 1));
            assertThat(status)
                .as("spoofed request %d must reach auth (401), not bypass into a fresh bucket", i + 1)
                .isEqualTo(401);
        }
        // 6-й с ещё одним «новым» X-Real-IP — тоже отбит: заголовок проигнорирован.
        int limited = login("sec86-spoof-" + UUID.randomUUID().toString().substring(0, 8),
            "WrongPass123!", "10.99.0.99");
        assertThat(limited)
            .as("6th request with a fresh spoofed X-Real-IP must still be 429")
            .isEqualTo(429);
    }

    @Test
    void data_spoofedRealIpFromUntrustedPeer_stillLimited() throws Exception {
        String token = adminToken();
        for (int i = 0; i < 3; i++) {
            int status = mockMvc.perform(get("/dmn")
                    .header("X-Real-IP", "10.99.0." + (i + 1))
                    .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus();
            assertThat(status)
                .as("spoofed data request %d must pass", i + 1)
                .isEqualTo(200);
        }
        int limited = mockMvc.perform(get("/dmn")
                .header("X-Real-IP", "10.99.0.99")
                .header("Authorization", "Bearer " + token))
            .andReturn().getResponse().getStatus();
        assertThat(limited)
            .as("4th data request with a fresh spoofed X-Real-IP must still be 429")
            .isEqualTo(429);
    }

    private int login(String username, String password, String realIp) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        MvcResult result = mockMvc.perform(post("/auth/login")
                .header("X-Real-IP", realIp)
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andReturn();
        return result.getResponse().getStatus();
    }

    private String adminToken() throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername("admin");
        dto.setPassword("admin");
        MvcResult result = mockMvc.perform(post("/auth/login")
                .header("X-Auth-Transport", "bearer")
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return mapper.readValue(result.getResponse().getContentAsString(),
            com.zorrodev.bpm.contract.dto.AuthResponse.class).getToken();
    }
}
