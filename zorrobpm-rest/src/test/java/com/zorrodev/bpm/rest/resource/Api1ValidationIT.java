package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-API-1, кластер 1: {@code @Valid} + constraints + exception-handler'ы.
 *
 * <p>Всё — через полный Spring-контекст + реальную цепочку (V11): каждый тест
 * бьёт HTTP в продовый wiring и ассертит код ответа. RED снимается на дереве
 * без фикса (мутация — сам прод-код).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Api1ValidationIT {

    @Autowired private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String adminToken;

    private String adminToken() throws Exception {
        if (adminToken == null) {
            LoginDTO dto = new LoginDTO();
            dto.setUsername("admin");
            dto.setPassword("admin");
            MvcResult r = mockMvc.perform(post("/auth/login")
                            .content(mapper.writeValueAsString(dto))
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk()).andReturn();
            adminToken = mapper.readTree(r.getResponse().getContentAsString()).get("token").asText();
        }
        return adminToken;
    }

    // ── Критерий 1: невалидное тело → 400, не 500 ──────────────────────

    @Test
    void assign_blankAssignee_400() throws Exception {
        // AssignUserTaskDTO.assignee мёртв без @Valid на эндпоинте
        mockMvc.perform(post("/user-tasks/" + UUID.randomUUID() + "/assign")
                        .header("Authorization", "Bearer " + adminToken())
                        .content("{\"assignee\":\"\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void login_blankCredentials_400() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .content("{\"username\":\"\",\"password\":\"\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void register_blankEmail_400() throws Exception {
        String body = "{\"username\":\"api1-" + UUID.randomUUID().toString().substring(0, 8)
                + "\",\"password\":\"MyStr0ng!P@ssw0rd\",\"fullName\":\"X\",\"email\":\"\"}";
        mockMvc.perform(post("/auth/register")
                        .content(body)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void complete_nullVariablesNoForm_400() throws Exception {
        // CompleteTaskDTO.variables=null без constraints: NPE-путь вместо 400.
        // Форма не нужна — сама десериализация/валидация DTO должна ответить 400.
        mockMvc.perform(post("/user-tasks/" + UUID.randomUUID() + "/complete")
                        .header("Authorization", "Bearer " + adminToken())
                        .content("{\"variables\":null}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    // ── Критерий 3: каждый новый handler через реальный прод-путь ──────

    @Test
    void malformedUuid_400_viaTypeMismatch() throws Exception {
        // GET /users/not-a-uuid: String→UUID mismatch обязан дать 400, не 500
        mockMvc.perform(get("/users/not-a-uuid")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void wrongMethod_405() throws Exception {
        // PUT /auth/login: метод не поддержан → 405, не 500
        mockMvc.perform(put("/auth/login")
                        .content("{}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void events_negativeLimit_400_viaConstraint() throws Exception {
        // GET /events?limit=-5: отрицательный лимит — 400 через явный guard
        // ресурса (не 200 с обрезанным окном и не 500 из глубины).
        // Форма тела — PagedDataDTO-обёртка (ресурс возвращает её всегда),
        // ошибка внутри data[0].
        mockMvc.perform(get("/events?limit=-5")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data[0].code").value("VALIDATION_ERROR"));
    }
}
