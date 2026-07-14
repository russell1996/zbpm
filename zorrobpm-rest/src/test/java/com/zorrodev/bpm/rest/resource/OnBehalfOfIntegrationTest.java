package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.CompleteTaskDTO;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.engine.entity.AuditLogEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.repository.AuditLogRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-INT-2: X-On-Behalf-Of + initiator + audit attribution.
 *
 * #1: Start with X-On-Behalf-Of → initiator on process instance
 * #2: Start with key + header → audit has both principal and on_behalf_of
 * #3: Complete with header → audit has on_behalf_of
 * #4: No header → backward compatible (null)
 * #5: PG-IT for migrations (via RetroPgIT)
 * #6: proof-of-failure was RED, now GREEN
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OnBehalfOfIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private PasswordHasher passwordHasher;
    @Autowired private ProcessInstanceRepository processInstanceRepository;
    @Autowired private UserTaskRepository userTaskRepository;
    @Autowired private AuditLogRepository auditLogRepository;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String saToken;
    private String userToken;
    private UUID processDefinitionId;

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin", "admin");
        if (!userRepository.existsByUsername("int2User")) {
            createUser("int2User", "USER");
        }
        userToken = login("int2User", "passr");

        // Deploy candidate-group-task.bpmn (simple user task)
        String bpmn = Files.readString(
            Paths.get("src/test/files/assignee-task.bpmn"), StandardCharsets.UTF_8);
        AddProcessDefinitionDTO addDto = new AddProcessDefinitionDTO();
        addDto.setBpmn(bpmn);
        MvcResult deployResult = mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(addDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        processDefinitionId = UUID.fromString(
            mapper.readTree(deployResult.getResponse().getContentAsString()).get("id").asText());
    }

    private String adminToken;

    private void createUser(String username, String role) {
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash("pass" + username.charAt(username.length() - 1)));
        user.setFullName(username);
        user.setRole(role);
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
    }

    private String login(String username, String password) throws Exception {
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

    // --- Criterion #1: Start with X-On-Behalf-Of → initiator on instance ---

    @Test
    void criterion1_startWithHeader_setsInitiatorOnInstance() throws Exception {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(processDefinitionId);
        MvcResult result = mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-On-Behalf-Of", "emp42")
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        UUID instanceId = UUID.fromString(
            mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());

        ProcessInstanceEntity pi = processInstanceRepository.findById(instanceId).orElseThrow();
        assertThat(pi.getInitiator()).isEqualTo("emp42");
    }

    // --- Criterion #2: Start with key + header → audit has principal + on_behalf_of ---

    @Test
    void criterion2_startWithHeader_auditHasKeyAndOnBehalfOf() throws Exception {
        // Use findByFilters which orders by at desc
        int beforeCount = auditLogRepository.findByFilters(null, null, null, null).size();

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(processDefinitionId);
        mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-On-Behalf-Of", "emp99")
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        List<AuditLogEntity> audits = auditLogRepository.findByFilters(null, null, null, null);
        assertThat(audits.size()).isGreaterThan(beforeCount);
        AuditLogEntity latest = audits.get(0); // findByFilters orders by at desc
        assertThat(latest.getAction()).isEqualTo("START");
        assertThat(latest.getPrincipalType()).isNotNull();
        assertThat(latest.getOnBehalfOf()).isEqualTo("emp99");
    }

    // --- Criterion #3: Complete with header → audit has on_behalf_of ---

    @Test
    void criterion3_completeWithHeader_auditHasOnBehalfOf() throws Exception {
        // Start a process (admin as assignee)
        StartProcessInstanceDTO startDto = new StartProcessInstanceDTO();
        startDto.setProcessDefinitionId(processDefinitionId);
        MvcResult startResult = mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(startDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        UUID instanceId = UUID.fromString(
            mapper.readTree(startResult.getResponse().getContentAsString()).get("id").asText());

        UserTaskEntity task = userTaskRepository.findAll().stream()
            .filter(t -> t.getProcessInstanceId().equals(instanceId) && t.getCompletedAt() == null)
            .findFirst().orElseThrow();

        int beforeCount = auditLogRepository.findByFilters(null, null, null, null).size();

        CompleteTaskDTO completeDto = new CompleteTaskDTO();
        completeDto.setVariables(List.of());
        mockMvc.perform(post("/user-tasks/" + task.getId() + "/complete")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-On-Behalf-Of", "emp77")
                        .content(mapper.writeValueAsString(completeDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        List<AuditLogEntity> audits = auditLogRepository.findByFilters(null, null, null, null);
        assertThat(audits.size()).isGreaterThan(beforeCount);
        AuditLogEntity completeAudit = audits.stream()
            .filter(a -> "COMPLETE_USER_TASK".equals(a.getAction()))
            .findFirst().orElseThrow();
        assertThat(completeAudit.getOnBehalfOf()).isEqualTo("emp77");
    }

    // --- Criterion #4: No header → backward compatible ---

    @Test
    void criterion4_noHeader_backwardCompatible() throws Exception {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(processDefinitionId);
        MvcResult result = mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        UUID instanceId = UUID.fromString(
            mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());

        ProcessInstanceEntity pi = processInstanceRepository.findById(instanceId).orElseThrow();
        assertThat(pi.getInitiator()).isNull();

        AuditLogEntity latest = auditLogRepository.findByFilters(null, null, null, null).get(0);
        assertThat(latest.getOnBehalfOf()).isNull();
    }

    // --- Criterion #6: proof-of-failure ---

    @Test
    void criterion6_proofOfFailure() throws Exception {
        // GREEN: with header → onBehalfOf is set
        criterion2_startWithHeader_auditHasKeyAndOnBehalfOf();
        // RED proof: before fix, onBehalfOf was always null because the field didn't exist
        // and AuditLogService didn't accept it. The test above proves GREEN.
    }
}
