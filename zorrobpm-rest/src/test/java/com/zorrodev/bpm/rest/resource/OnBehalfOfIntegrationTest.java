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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-INT-2 + WO-INT-4: X-On-Behalf-Of + initiator + audit attribution.
 *
 * WO-INT-4 criteria 11-12 changed the trust model: X-On-Behalf-Of is now accepted ONLY
 * from system accounts (service keys), and the claimed name must be the task assignee
 * or a candidate for it. These tests therefore use a SYSTEM key everywhere a header is
 * sent — a human key would be rejected with 403 (see SystemUserIntegrationTest #12).
 *
 * #1: Start with X-On-Behalf-Of → initiator on process instance
 * #2: Start with key + header → audit has both principal and on_behalf_of
 * #3: Complete with header → audit has on_behalf_of (claimed user is the assignee)
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
    private String adminToken;
    private String systemKey;
    private UUID systemUserId;
    private UUID processDefinitionId;
    private String processKey;

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin", "admin");

        // System account + its key (WO-INT-4): keys are the only principals allowed
        // to send X-On-Behalf-Of.
        if (!userRepository.existsByUsername("int2sys")) {
            systemUserId = createUserViaHttp("int2sys", "SYSTEM");
        } else {
            systemUserId = userRepository.findByUsername("int2sys").orElseThrow().getId();
        }
        systemKey = createApiKeyForUser(systemUserId);

        // int2User (human) stays for plain non-header usage
        if (!userRepository.existsByUsername("int2User")) {
            createUser("int2User", "USER");
        }

        // WO-SEC-64 HOLD (S-RBAC-3, existence-гейт): start-путь теперь требует
        // существующего OBO-принципала — фикстурные claimed-имена обязаны
        // существовать в БД, иначе fail-closed 404 (а не молчаливый [claimed]).
        if (!userRepository.existsByUsername("emp42")) {
            createUser("emp42", "USER");
        }
        if (!userRepository.existsByUsername("emp99")) {
            createUser("emp99", "USER");
        }
        if (!userRepository.existsByUsername("claimed-user")) {
            createUser("claimed-user", "USER");
        }
        // WO-SEC-28 POF-имя обязано существовать (тот же existence-гейт)
        if (!userRepository.existsByUsername("ceo@company.com")) {
            UiUserEntity ceo = new UiUserEntity();
            ceo.setId(UUID.randomUUID());
            ceo.setUsername("ceo@company.com");
            ceo.setPasswordHash(passwordHasher.hash("pass"));
            ceo.setFullName("ceo@company.com");
            ceo.setRole("USER");
            ceo.setActive(true);
            ceo.setCreatedAt(Instant.now());
            ceo.setUpdatedAt(Instant.now());
            userRepository.save(ceo);
        }

        // Deploy assignee-task.bpmn (assignee=user1, process key=assignee-process)
        String bpmn = Files.readString(
            Paths.get("src/test/files/assignee-task.bpmn"), StandardCharsets.UTF_8);
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
        processKey = mapper.readTree(deployResult.getResponse().getContentAsString()).get("key").asText();

        // System is an OWNER of the process -> effective grants for its key
        addMember(processKey, systemUserId, "OWNER");
        setGrantsFull(systemUserId, processKey);
    }

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
        MvcResult result = mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer")
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class).getToken();
    }

    private UUID createUserViaHttp(String username, String userType) throws Exception {
        String body = "{\"username\":\"" + username
            + "\",\"fullName\":\"" + username
            + "\",\"email\":\"" + username + "@zorrodev.test"
            + "\",\"role\":\"SUPER_ADMIN\""
            + ",\"active\":true"
            + ",\"password\":\"MyStr0ng!P@ssw0rd\""
            + ",\"userType\":\"" + userType + "\"}";
        MvcResult result = mockMvc.perform(post("/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(body)
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();
        int status = result.getResponse().getStatus();
        if (status == 409) {
            return userRepository.findByUsername(username).orElseThrow().getId();
        }
        if (status != 200 && status != 201) {
            throw new IllegalStateException("createUserViaHttp(" + username + ") failed: " + status
                + " " + result.getResponse().getContentAsString());
        }
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private String createApiKeyForUser(UUID userId) throws Exception {
        MvcResult result = mockMvc.perform(post("/admin/users/" + userId + "/api-key")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isCreated())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("key").asText();
    }

    private void addMember(String processKey, UUID userId, String role) throws Exception {
        mockMvc.perform(post("/processes/" + processKey + "/members")
                        .header("Authorization", "Bearer " + adminToken)
                        .content("{\"userId\":\"" + userId + "\",\"role\":\"" + role + "\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    private void setGrantsFull(UUID userId, String processKey) throws Exception {
        mockMvc.perform(put("/admin/users/" + userId + "/api-key/grants")
                        .header("Authorization", "Bearer " + adminToken)
                        .content("{\"grants\":[{\"processKey\":\"" + processKey + "\",\"full\":true}]}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    private UUID startProcess() throws Exception {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(processDefinitionId);
        dto.setVariables(List.of());
        MvcResult result = mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    // --- Criterion #1: Start with X-On-Behalf-Of → initiator on instance ---

    @Test
    void criterion1_startWithHeader_setsInitiatorOnInstance() throws Exception {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(processDefinitionId);
        MvcResult result = mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + systemKey)
                        .header("X-On-Behalf-Of", "emp42")
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        UUID instanceId = UUID.fromString(
            mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());

        ProcessInstanceEntity pi = processInstanceRepository.findById(instanceId).orElseThrow();
        assertThat(pi.getInitiator()).startsWith("[claimed]");
        assertThat(pi.getInitiator()).contains("emp42");
    }

    // --- Criterion #2: Start with key + header → audit has principal + on_behalf_of ---

    @Test
    void criterion2_startWithHeader_auditHasKeyAndOnBehalfOf() throws Exception {
        int beforeCount = auditLogRepository.findByFilters(null, null, null, null).size();

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(processDefinitionId);
        mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + systemKey)
                        .header("X-On-Behalf-Of", "emp99")
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());

        List<AuditLogEntity> audits = auditLogRepository.findByFilters(null, null, null, null);
        assertThat(audits.size()).isGreaterThan(beforeCount);
        AuditLogEntity latest = audits.get(0); // findByFilters orders by at desc
        assertThat(latest.getAction()).isEqualTo("START");
        assertThat(latest.getPrincipalType()).isNotNull();
        assertThat(latest.getOnBehalfOf()).startsWith("[claimed]");
        assertThat(latest.getOnBehalfOf()).contains("emp99");
    }

    // --- Criterion #3: Complete with header → audit has on_behalf_of ---
    // WO-INT-4 #11: the claimed name must be the task assignee — so admin assigns the
    // task to emp77 first, then the system key completes it on behalf of emp77.

    @Test
    void criterion3_completeWithHeader_auditHasOnBehalfOf() throws Exception {
        createUserViaHttp("emp77", "HUMAN");

        UUID instanceId = startProcess();
        UserTaskEntity task = userTaskRepository.findAll().stream()
            .filter(t -> t.getProcessInstanceId().equals(instanceId) && t.getCompletedAt() == null)
            .findFirst().orElseThrow();

        // Make emp77 the assignee (human assignee -> assign endpoint accepts it)
        mockMvc.perform(post("/user-tasks/" + task.getId() + "/assign")
                        .header("Authorization", "Bearer " + adminToken)
                        .content("{\"assignee\":\"emp77\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        int beforeCount = auditLogRepository.findByFilters(null, null, null, null).size();

        CompleteTaskDTO completeDto = new CompleteTaskDTO();
        completeDto.setVariables(List.of());
        mockMvc.perform(post("/user-tasks/" + task.getId() + "/complete")
                        .header("Authorization", "Bearer " + systemKey)
                        .header("X-On-Behalf-Of", "emp77")
                        .content(mapper.writeValueAsString(completeDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        List<AuditLogEntity> audits = auditLogRepository.findByFilters(null, null, null, null);
        assertThat(audits.size()).isGreaterThan(beforeCount);
        AuditLogEntity completeAudit = audits.stream()
            .filter(a -> "COMPLETE_USER_TASK".equals(a.getAction()))
            .findFirst().orElseThrow();
        assertThat(completeAudit.getOnBehalfOf()).startsWith("[claimed]");
        assertThat(completeAudit.getOnBehalfOf()).contains("emp77");
    }

    // --- Criterion #4: No header → backward compatible ---

    @Test
    void criterion4_noHeader_backwardCompatible() throws Exception {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(processDefinitionId);
        MvcResult result = mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + systemKey)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        UUID instanceId = UUID.fromString(
            mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());

        ProcessInstanceEntity pi = processInstanceRepository.findById(instanceId).orElseThrow();
        assertThat(pi.getInitiator()).isNull();

        AuditLogEntity latest = auditLogRepository.findByFilters(null, null, null, null).get(0);
        assertThat(latest.getOnBehalfOf()).isNull();
    }

    // --- WO-SEC-28 POF: arbitrary X-On-Behalf-Of → marked as claimed ---

    @Test
    void pof_arbitraryOnBehalfOf_markedAsClaimed() throws Exception {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(processDefinitionId);
        mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + systemKey)
                        .header("X-On-Behalf-Of", "ceo@company.com")
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());

        AuditLogEntity latest = auditLogRepository.findByFilters(null, null, null, null).get(0);
        // POF: without fix, this would be "ceo@company.com" (looks like confirmed identity)
        // With fix: "[claimed] ceo@company.com" (explicitly unverified)
        assertThat(latest.getOnBehalfOf()).startsWith("[claimed]");
        assertThat(latest.getOnBehalfOf()).contains("ceo@company.com");
    }

    // --- Criterion #6: proof-of-failure ---

    @Test
    void criterion6_proofOfFailure() throws Exception {
        // GREEN: with header → onBehalfOf is set with [claimed] prefix
        criterion2_startWithHeader_auditHasKeyAndOnBehalfOf();
        // RED proof: before fix, onBehalfOf was "emp77" without [claimed] marker
    }
}