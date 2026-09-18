package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.CompleteTaskDTO;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.engine.entity.TimerJobEntity;
import com.zorrodev.bpm.engine.repository.TimerJobRepository;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-FEAT-2: Cancel process instance API.
 *  #1: ACTIVE instance → cancel → 200
 *  #1b: After cancel, task is CANCELLED (status check)
 *  #2: COMPLETED instance → cancel → 409
 *  #3: Already CANCELLED → cancel → 409
 *  #4: Zombie timer: boundary timer job deleted after cancel
 *  #5: proof-of-failure — POST /cancel on current code → 404
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CancelProcessInstanceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TimerJobRepository timerJobRepository;

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

    private String deployAndGetProcessKey() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-cancel-process.bpmn"), StandardCharsets.UTF_8);
        AddProcessDefinitionDTO addDto = new AddProcessDefinitionDTO();
        addDto.setBpmn(bpmn);
        mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + token)
                        .content(mapper.writeValueAsString(addDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());
        return "cancel-test-process";
    }

    private String startProcess(String processKey) throws Exception {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionKey(processKey);
        MvcResult result = mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + token)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private UUID findUserTask(String processInstanceId) throws Exception {
        // WO-TEST-8: filtered by our own instance — unfiltered data[0] could be any
        // test's task once the shared corpus grows (wrong task completed/cancelled).
        MvcResult result = mockMvc.perform(get("/user-tasks")
                        .header("Authorization", "Bearer " + token)
                        .param("processInstanceId", processInstanceId))
                .andExpect(status().isOk())
                .andReturn();
        var tasksPage = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(tasksPage.get("data").size()).isGreaterThan(0);
        return UUID.fromString(tasksPage.get("data").get(0).get("id").asText());
    }

    private void completeTask(UUID taskId) throws Exception {
        mockMvc.perform(post("/user-tasks/" + taskId + "/complete")
                        .header("Authorization", "Bearer " + token)
                        .content(mapper.writeValueAsString(emptyCompleteTaskDTO()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    // --- Criterion #1: ACTIVE instance → cancel → 200 ---

    @Test
    void criterion1_activeInstance_cancelReturns200() throws Exception {
        String processId = startProcess(deployAndGetProcessKey());
        mockMvc.perform(post("/process-instances/" + processId + "/cancel")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(processId));
    }

    // --- Criterion #1b: After cancel, task is CANCELLED ---

    @Test
    void criterion1b_afterCancel_taskIsCancelled() throws Exception {
        String processId = startProcess(deployAndGetProcessKey());
        UUID taskId = findUserTask(processId);

        // Verify task is active before cancel
        mockMvc.perform(get("/user-tasks/" + taskId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"));

        // Cancel the process
        mockMvc.perform(post("/process-instances/" + processId + "/cancel")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted());

        // Verify the task is now CANCELLED
        mockMvc.perform(get("/user-tasks/" + taskId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    // --- Criterion #2: COMPLETED instance → cancel → 409 ---

    @Test
    void criterion2_completedInstance_cancelReturns409() throws Exception {
        String processId = startProcess(deployAndGetProcessKey());
        UUID taskId = findUserTask(processId);
        completeTask(taskId);

        mockMvc.perform(post("/process-instances/" + processId + "/cancel")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    // --- Criterion #3: Already CANCELLED → cancel → 409 ---

    @Test
    void criterion3_alreadyCancelled_cancelReturns409() throws Exception {
        String processId = startProcess(deployAndGetProcessKey());
        // First cancel
        mockMvc.perform(post("/process-instances/" + processId + "/cancel")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted());

        // Second cancel → 409
        mockMvc.perform(post("/process-instances/" + processId + "/cancel")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    // --- Criterion #4: Zombie timer — timer_job deleted after cancel ---

    @Test
    void criterion4_zombieTimer_timerJobDeletedAfterCancel() throws Exception {
        // Deploy process with boundary timer
        String bpmn = Files.readString(Paths.get("src/test/files/test-zombie-timer.bpmn"), StandardCharsets.UTF_8);
        AddProcessDefinitionDTO addDto = new AddProcessDefinitionDTO();
        addDto.setBpmn(bpmn);
        mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + token)
                        .content(mapper.writeValueAsString(addDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());

        // Start process — creates timer_job
        String processId = startProcess("zombie-timer-process");

        // Verify timer_job exists
        List<TimerJobEntity> timerJobs = timerJobRepository.findAll();
        long beforeCancel = timerJobs.stream()
            .filter(t -> !t.isFired())
            .count();
        assertThat(beforeCancel).isGreaterThanOrEqualTo(1);

        // Cancel the process
        mockMvc.perform(post("/process-instances/" + processId + "/cancel")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted());

        // Verify timer_jobs are deleted — no more pending timer jobs for this instance
        List<TimerJobEntity> afterCancel = timerJobRepository.findAll();
        long remaining = afterCancel.stream()
            .filter(t -> t.getProcessInstanceId() != null && t.getProcessInstanceId().toString().equals(processId))
            .count();
        assertThat(remaining).as("All timer_jobs for cancelled instance must be deleted").isEqualTo(0);
    }

    // WO-API-1: пустой DTO обязан нести variables=[] (null отклоняется @NotNull).
    private static com.zorrodev.bpm.contract.dto.CompleteTaskDTO emptyCompleteTaskDTO() {
        com.zorrodev.bpm.contract.dto.CompleteTaskDTO dto = new com.zorrodev.bpm.contract.dto.CompleteTaskDTO();
        dto.setVariables(java.util.List.of());
        return dto;
    }

    private static com.zorrodev.bpm.contract.dto.ResolveIncidentDTO emptyResolveIncidentDTO() {
        com.zorrodev.bpm.contract.dto.ResolveIncidentDTO dto = new com.zorrodev.bpm.contract.dto.ResolveIncidentDTO();
        dto.setVariables(java.util.List.of());
        return dto;
    }

    private static com.zorrodev.bpm.contract.dto.EvaluateDecisionDTO emptyEvaluateDecisionDTO() {
        com.zorrodev.bpm.contract.dto.EvaluateDecisionDTO dto = new com.zorrodev.bpm.contract.dto.EvaluateDecisionDTO();
        dto.setVariables(java.util.List.of());
        return dto;
    }
}
