package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-C8-21 раунд 2, критерий 2 (форма): живая проверка, что недосозданная задача
 * (creating-фаза в полёте) отдаёт 404 и на форме, а не 200 с {@code {"type":"none"}}.
 * Полный HTTP-путь через реальный стек: деплой, старт, activityId из репозитория
 * (строки задачи в середине фазы нет — взять id из списка нельзя), GET формы.
 */
@SpringBootTest(classes = TestMain.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("test")
class CreatingPhaseFormIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ActivityRepository activityRepository;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String adminToken;

    @BeforeAll
    void setup() throws Exception {
        adminToken = loginAndGetToken("admin", "admin");
    }

    @Test
    void midPhaseForm_notFound() throws Exception {
        // WO-C8-21r2: user task с creating-listener, фаза открыта — формы ещё нет.
        String key = "c8r2form-" + UUID.randomUUID().toString().substring(0, 8);
        String bpmn = Files.readString(Paths.get("src/test/files/c8r2-midphase-form.bpmn"))
            .replace("c8r2-midphase-form", key);
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn(bpmn);
        MvcResult deployed = mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        UUID pdId = UUID.fromString(mapper.readTree(deployed.getResponse().getContentAsString()).get("id").asText());

        StartProcessInstanceDTO start = new StartProcessInstanceDTO();
        start.setProcessDefinitionId(pdId);
        start.setVariables(List.of());
        MvcResult started = mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(start))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        UUID piId = UUID.fromString(mapper.readTree(started.getResponse().getContentAsString()).get("id").asText());

        // Строки задачи нет (раунд 2) — id берём из активности review; фаза идёт.
        List<ActivityEntity> activities = activityRepository.findByProcessInstanceIdOrderByCreatedAtAsc(piId).stream()
            .filter(a -> "review".equals(a.getBpmnElementId()))
            .toList();
        assertThat(activities).hasSize(1);
        UUID activityId = activities.get(0).getId();

        // Живой 404 — тот же путь, что у несуществующей задачи (findById → orElseThrow).
        mockMvc.perform(get("/user-tasks/" + activityId + "/form")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());

        // И список пуст на HTTP-уровне (невидимость end-to-end, не только движковая).
        MvcResult list = mockMvc.perform(get("/user-tasks")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("processInstanceId", piId.toString()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(mapper.readTree(list.getResponse().getContentAsString()).get("data")).isEmpty();
    }

    private String loginAndGetToken(String username, String password) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class).getToken();
    }
}
