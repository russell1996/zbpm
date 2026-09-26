package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import org.junit.jupiter.api.BeforeAll;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-DIFF-6: диагностический поллинг list-эндпоинтов не должен упираться в 429.
 *
 * <p>Raxon-харнесс (S-018) поллит {@code GET /service-tasks} каждые 200мс
 * (~150 запросов за 15с) и получал 429 на 121-м запросе при старом дефолте
 * data-бакета 120/60с. После поднятия до 300/60с те же 150 запросов проходят.
 *
 * <p>V11: полный Spring-контекст + реальная цепочка фильтров (MockMvc).
 * {@code data-capacity} намеренно НЕ переопределён в {@code @TestPropertySource} —
 * тест гоняет настоящий прод-дефолт из {@code @Value} в
 * {@code RateLimitFilterConfig}, а не вписанное в тест число. Откат дефолта
 * 300-&gt;120 валит {@code criterion2} (429 на 121-м) — проверено как POF.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
    "zorrobpm.security.rate-limit.enabled=true"
})
class Diff6ServiceTaskPollingRateLimitIT {

    @Autowired MockMvc mockMvc;
    @Autowired com.zorrodev.bpm.rest.security.RateLimitFilter rateLimitFilter;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String adminToken;

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin", "admin");
    }

    @org.junit.jupiter.api.BeforeEach
    void resetBuckets() {
        rateLimitFilter.reset();
    }

    private String login(String u, String p) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(u);
        dto.setPassword(p);
        MvcResult r = mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer")
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    private int pollServiceTasks() throws Exception {
        MvcResult r = mockMvc.perform(get("/service-tasks")
                        .header("Authorization", "Bearer " + adminToken))
                .andReturn();
        return r.getResponse().getStatus();
    }

    // ── Критерий 2 WO: ~150 запросов за 15с к list-эндпоинту без 429 ──

    @Test
    void criterion2_pollingBurst150_no429_onProdDefault() throws Exception {
        // Raxon S-018: ~150 запросов подряд с одного IP (burst строже их 15с-растяжки).
        for (int i = 0; i < 150; i++) {
            int s = pollServiceTasks();
            assertThat(s)
                .as("Polling request %d must pass on prod default 300/60s (not 429)", i)
                .isEqualTo(200);
        }
    }

    // ── Гейт жив: переполнение нового дефолта всё ещё даёт 429 ──

    @Test
    void gateAlive_301stRequest_returns429() throws Exception {
        for (int i = 0; i < 300; i++) {
            int s = pollServiceTasks();
            assertThat(s)
                .as("Within prod default capacity request %d should pass", i)
                .isEqualTo(200);
        }
        int s = pollServiceTasks();
        assertThat(s)
            .as("Exceeding prod default 300/60s must return 429")
            .isEqualTo(429);
    }
}
