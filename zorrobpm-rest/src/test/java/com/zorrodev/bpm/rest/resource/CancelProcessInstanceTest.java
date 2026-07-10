package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-FEAT-2: Cancel process instance API.
 *  #1: ACTIVE instance → cancel → 200
 *  #2: COMPLETED instance → cancel → 409
 *  #3: Already CANCELLED → cancel → 409
 *  #4: proof-of-failure — POST /cancel on current code → 404
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CancelProcessInstanceTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String token;

    @BeforeAll
    void login() throws Exception {
        LoginDTO loginDTO = new LoginDTO();
        loginDTO.setUsername("admin");
        loginDTO.setPassword("admin");

        MvcResult result = mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(loginDTO))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        AuthResponse authResponse = mapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class);
        token = authResponse.getToken();
    }

    private String startProcessWithUserTask() throws Exception {
        // Deploy cancel-test-process.bpmn
        String bpmn = Files.readString(Paths.get("src/test/files/test-cancel-process.bpmn"), StandardCharsets.UTF_8);
        AddProcessDefinitionDTO addDto = new AddProcessDefinitionDTO();
        addDto.setBpmn(bpmn);
        mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + token)
                        .content(mapper.writeValueAsString(addDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionKey("cancel-test-process");
        MvcResult result = mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + token)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private void completeTask(UUID taskId) throws Exception {
        mockMvc.perform(post("/user-tasks/" + taskId + "/complete")
                        .header("Authorization", "Bearer " + token)
                        .content(mapper.writeValueAsString(new com.zorrodev.bpm.contract.dto.CompleteTaskDTO()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    private UUID findUserTask() throws Exception {
        MvcResult result = mockMvc.perform(get("/user-tasks")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        var tasksPage = mapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(tasksPage.get("data").get(0).get("id").asText());
    }

    // --- Criterion #1: ACTIVE instance → cancel → 200 ---

    @Test
    void criterion1_activeInstance_cancelReturns200() throws Exception {
        String processId = startProcessWithUserTask();
        mockMvc.perform(post("/process-instances/" + processId + "/cancel")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(processId));
    }

    // --- Criterion #2: COMPLETED instance → cancel → 409 ---

    @Test
    void criterion2_completedInstance_cancelReturns409() throws Exception {
        String processId = startProcessWithUserTask();
        UUID taskId = findUserTask();
        completeTask(taskId);

        mockMvc.perform(post("/process-instances/" + processId + "/cancel")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    // --- Criterion #3: Already CANCELLED → cancel → 409 ---

    @Test
    void criterion3_alreadyCancelled_cancelReturns409() throws Exception {
        String processId = startProcessWithUserTask();
        // First cancel
        mockMvc.perform(post("/process-instances/" + processId + "/cancel")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Second cancel → 409
        mockMvc.perform(post("/process-instances/" + processId + "/cancel")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }
}
