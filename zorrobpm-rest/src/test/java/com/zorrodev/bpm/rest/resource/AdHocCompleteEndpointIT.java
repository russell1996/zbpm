package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.*;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * WO-C8-33: {@code POST /service-tasks/{id}/complete-adhoc} binding — full stack
 * (real filter, services, DB). The engine semantics are proven in
 * {@code AdHocJobWorkerIntegrationTests}; here the route, the DTO shape, authz and
 * the error codes (409 AD_HOC_JOB_STALE / 400 / 404).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdHocCompleteEndpointIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private ProcessMemberRepository processMemberRepository;
    @Autowired private ProcessRepository processRepository;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private VariableRepository variableRepository;
    @Autowired private PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String adminToken;
    private String viewerToken;
    private UUID processDefinitionId;

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin", "admin");
        UiUserEntity viewer = createAndSaveUser("adhocviewer");
        viewerToken = login("adhocviewer", "passr");

        String bpmn = Files.readString(
            Paths.get("src/test/files/test-adhoc-job-worker.bpmn"), StandardCharsets.UTF_8);
        AddProcessDefinitionDTO addDto = new AddProcessDefinitionDTO();
        addDto.setBpmn(bpmn);
        MvcResult deployResult = mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(addDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        processDefinitionId = UUID.fromString(
            mapper.readTree(deployResult.getResponse().getContentAsString()).get("id").asText());
        ProcessEntity process = processRepository.findByDefinitionKey(
            mapper.readTree(deployResult.getResponse().getContentAsString()).get("key").asText()).orElseThrow();
        addMember(process.getId(), viewer.getId(), "VIEWER");
    }

    @Test
    void completeAdhoc_happy_activatesElement() throws Exception {
        UUID pi = startInstance();
        UUID scopeId = scopeActivity(pi);
        String token = jobToken(pi, scopeId);

        mockMvc.perform(post("/service-tasks/" + scopeId + "/complete-adhoc")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(result(token, false, false, "taskX")))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        assertThat(activeTasks(pi, "taskX")).hasSize(1);
    }

    @Test
    void completeAdhoc_staleToken_returns409WithCode() throws Exception {
        UUID pi = startInstance();
        UUID scopeId = scopeActivity(pi);
        String token1 = jobToken(pi, scopeId);

        mockMvc.perform(post("/service-tasks/" + scopeId + "/complete-adhoc")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(result(token1, false, false, "taskX")))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        mockMvc.perform(post("/service-tasks/" + scopeId + "/complete-adhoc")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(result(token1, false, false, "taskY")))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AD_HOC_JOB_STALE"));
        assertThat(activeTasks(pi, "taskY")).isEmpty();
    }

    @Test
    void completeAdhoc_fulfilledWithActivation_returns400() throws Exception {
        UUID pi = startInstance();
        UUID scopeId = scopeActivity(pi);

        mockMvc.perform(post("/service-tasks/" + scopeId + "/complete-adhoc")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(result(jobToken(pi, scopeId), true, false, "taskX")))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AD_HOC_RESULT_CONTRADICTION"));
    }

    @Test
    void completeAdhoc_unknownId_returns404() throws Exception {
        mockMvc.perform(post("/service-tasks/" + UUID.randomUUID() + "/complete-adhoc")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(result("nope", false, false)))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void completeAdhoc_viewerWithoutGrant_returns403() throws Exception {
        UUID pi = startInstance();
        UUID scopeId = scopeActivity(pi);

        mockMvc.perform(post("/service-tasks/" + scopeId + "/complete-adhoc")
                        .header("Authorization", "Bearer " + viewerToken)
                        .content(mapper.writeValueAsString(result(jobToken(pi, scopeId), false, false, "taskX")))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
        assertThat(activeTasks(pi, "taskX")).isEmpty();
    }

    // --- Helpers ---

    private AdHocJobResultDTO result(String jobToken, boolean fulfilled, boolean cancel, String... ids) {
        AdHocJobResultDTO dto = new AdHocJobResultDTO();
        dto.setJobToken(jobToken);
        dto.setIsCompletionConditionFulfilled(fulfilled);
        dto.setIsCancelRemainingInstances(cancel);
        dto.setActivateElements(java.util.Arrays.stream(ids).map(id -> {
            AdHocActivateElementDTO el = new AdHocActivateElementDTO();
            el.setElementId(id);
            return el;
        }).toList());
        return dto;
    }

    private UUID startInstance() throws Exception {
        StartProcessInstanceDTO startDto = new StartProcessInstanceDTO();
        startDto.setProcessDefinitionId(processDefinitionId);
        startDto.setVariables(List.of());
        MvcResult startResult = mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(startDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(
            mapper.readTree(startResult.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID scopeActivity(UUID pi) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi) && a.getBpmnElementId().equals("adhoc"))
            .map(ActivityEntity::getId).findFirst().orElseThrow();
    }

    private String jobToken(UUID pi, UUID scopeId) {
        ActivityEntity scope = activityRepository.findById(scopeId).orElseThrow();
        return variableRepository.findByProcessInstanceId(pi).stream()
            .filter(v -> ("_adhoc_job_" + scope.getId()).equals(v.getName()))
            .map(ProcessVariableEntity::getTextValue).findFirst().orElseThrow();
    }

    private List<ActivityEntity> activeTasks(UUID pi, String elementId) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi) && a.getBpmnElementId().equals(elementId)
                && a.getStatus() == ActivityStatus.CREATED)
            .toList();
    }

    private void addMember(UUID processId, UUID userId, String role) {
        ProcessMemberEntity pm = new ProcessMemberEntity();
        pm.setProcessId(processId);
        pm.setUserId(userId);
        pm.setRole(role);
        pm.setAddedBy(userId);
        pm.setAddedAt(Instant.now());
        processMemberRepository.save(pm);
    }

    private UiUserEntity createAndSaveUser(String username) {
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash("passr"));
        user.setFullName(username);
        user.setRole("USER");
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user);
    }

    private String login(String username, String password) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        MvcResult result = mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer")
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class).getToken();
    }
}
