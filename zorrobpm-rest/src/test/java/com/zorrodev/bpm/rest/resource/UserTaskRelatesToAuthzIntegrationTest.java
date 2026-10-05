package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.entity.UserGroupEntity;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.repository.UserGroupRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-IN-2 criterion 4 — the authorization boundary of {@code relatesTo}.
 *
 * <p>V11: full {@code @SpringBootTest} + {@code @AutoConfigureMockMvc}, the REAL filter chain and
 * the REAL database — no mocked principal, no mocked resolver. Three principals with genuinely
 * different powers:
 * <ul>
 *   <li>{@code in2Alice} / {@code in2Bob} — plain {@code USER}s, members of the process;</li>
 *   <li>{@code admin} — SUPER_ADMIN (see-all).</li>
 * </ul>
 *
 * <p>POF (G-K): without the guard, criterion 2 answers 200 with Alice's task ids to Bob — a real
 * data leak, not a shape difference. The mutation that must break these tests is "drop the
 * {@code relatesTo} authorization check in QueryResource.getUserTasks".
 *
 * <p>Criterion 4 is the one that can be faked: {@code relatesTo} must NOT become a way around
 * {@code allowedPdIds}. Bob is NOT a member of the second process, and the task assigned to him
 * there must stay invisible even though {@code ?relatesTo=<bob>} is perfectly legal for him.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserTaskRelatesToAuthzIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private PasswordHasher passwordHasher;
    @Autowired private UserTaskRepository userTaskRepository;
    @Autowired private UserGroupRepository userGroupRepository;
    @Autowired private ProcessRepository processRepository;
    @Autowired private ProcessMemberRepository processMemberRepository;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String adminToken;
    private String aliceToken;
    private String bobToken;
    private String strangerToken;

    private UUID aliceId;
    private UUID bobId;
    private UUID strangerId;

    private UUID pdVisible;    // alice, bob and stranger are members
    private UUID pdBobOnly;    // ONLY bob is a member — stranger's task must stay invisible
    private UUID pdForeign;    // nobody is a member

    private UUID aliceTaskInVisible;
    private UUID aliceTaskViaGroupInVisible;
    private UUID strangerTaskInBobOnly;
    private UUID strangerTaskInForeign;

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin", "admin");

        aliceId = createUser("in2RestAlice").getId();
        bobId = createUser("in2RestBob").getId();
        strangerId = createUser("in2RestStranger").getId();
        aliceToken = login("in2RestAlice", "secret-in2");
        bobToken = login("in2RestBob", "secret-in2");
        strangerToken = login("in2RestStranger", "secret-in2");

        // alice is a candidate of group "in2RestSales" — the group leg of relatesTo
        UserGroupEntity aliceGroup = new UserGroupEntity();
        aliceGroup.setUserId(aliceId);
        aliceGroup.setGroupName("in2RestSales");
        userGroupRepository.save(aliceGroup);

        pdVisible = deploy("in2-rest-visible");
        pdBobOnly = deploy("in2-rest-bob-only");
        pdForeign = deploy("in2-rest-foreign");
        for (String key : List.of("in2-rest-visible", "in2-rest-bob-only", "in2-rest-foreign")) {
            addMember(aliceId, key);
            addMember(bobId, key);
            addMember(strangerId, key);
        }
        // stranger loses membership in the other two — allowedPdIds must then hide those tasks
        processMemberRepository.deleteAll(processMemberRepository
            .findByProcessIdInAndUserId(
                List.of(processIdOf("in2-rest-bob-only"), processIdOf("in2-rest-foreign")),
                strangerId));

        aliceTaskInVisible = task(pdVisible, "in2RestAlice", null);
        aliceTaskViaGroupInVisible = task(pdVisible, "in2RestBob", "in2RestSales");
        strangerTaskInBobOnly = task(pdBobOnly, "in2RestStranger", null);
        strangerTaskInForeign = task(pdForeign, "in2RestStranger", null);
    }

    // ===== criterion 1: a user may always ask about their OWN tasks =====

    @Test
    void relatesTo_ownUserId_returnsOwnTasksOnly() throws Exception {
        List<UUID> ids = userTaskIds(bobToken, bobId.toString());

        assertThat(ids).contains(aliceTaskViaGroupInVisible);
        assertThat(ids).doesNotContain(aliceTaskInVisible, strangerTaskInBobOnly);
    }

    @Test
    void relatesTo_ownUserId_includesTheGroupLeg() throws Exception {
        List<UUID> ids = userTaskIds(aliceToken, aliceId.toString());

        assertThat(ids).contains(aliceTaskInVisible, aliceTaskViaGroupInVisible);
    }

    // ===== criterion 2 (POF): another user's tasks are NOT reachable =====

    @Test
    void relatesTo_otherUserId_returns403() throws Exception {
        mockMvc.perform(get("/user-tasks")
                        .header("Authorization", "Bearer " + bobToken)
                        .param("relatesTo", aliceId.toString()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void relatesTo_otherUserId_secondUnprivilegedUserIsAlsoDenied() throws Exception {
        // a plain USER with no SUPER_ADMIN powers must not reach Alice either
        mockMvc.perform(get("/user-tasks")
                        .header("Authorization", "Bearer " + strangerToken)
                        .param("relatesTo", aliceId.toString()))
            .andExpect(status().isForbidden());
    }

    // ===== criterion 3: SUPER_ADMIN (see-all) keeps the unrestricted power =====

    @Test
    void relatesTo_superAdmin_mayAskAboutAnyone() throws Exception {
        List<UUID> ids = userTaskIds(adminToken, strangerId.toString());

        assertThat(ids).contains(strangerTaskInBobOnly, strangerTaskInForeign);
    }

    // ===== criterion 4: relatesTo must NOT widen allowedPdIds =====

    @Test
    void relatesTo_doesNotBypassProcessScope() throws Exception {
        // stranger legitimately asks about their OWN tasks, but a task assigned to them in a
        // process they are NOT a member of must stay invisible — the very same query without
        // allowedPdIds would return it.
        List<UUID> ids = userTaskIds(strangerToken, strangerId.toString());

        assertThat(ids).doesNotContain(strangerTaskInBobOnly, strangerTaskInForeign);
    }

    @Test
    void withoutRelatesTo_strangerSeesOnlyItsOwnProcessTasks() throws Exception {
        mockMvc.perform(get("/user-tasks")
                        .header("Authorization", "Bearer " + strangerToken))
            .andExpect(status().isOk());
        List<UUID> ids = userTaskIds(strangerToken, null);

        assertThat(ids).doesNotContain(strangerTaskInBobOnly, strangerTaskInForeign);
    }

    // ===== the new filters are reachable over HTTP and validated there =====

    @Test
    void candidateGroupParam_overHttp_filtersRows() throws Exception {
        mockMvc.perform(get("/user-tasks")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("candidateGroup", "in2RestSales")
                        .param("processInstanceId", instanceOf(aliceTaskViaGroupInVisible).toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void bpmnElementIdAndFormKey_overHttp_filterRows() throws Exception {
        mockMvc.perform(get("/user-tasks")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("bpmnElementId", "reviewTask")
                        .param("formKey", "form-in2-rest")
                        .param("pageSize", "200"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].formKey").value("form-in2-rest"));
    }

    @Test
    void sortBy_unknownField_overHttp_is400() throws Exception {
        mockMvc.perform(get("/user-tasks")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("sortBy", "assignee; drop table user_tasks --"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("UNSUPPORTED_SORT_FIELD"));
    }

    @Test
    void sortBy_whitelistedField_overHttp_is200() throws Exception {
        mockMvc.perform(get("/user-tasks")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("sortBy", "assignee")
                        .param("sortOrder", "asc")
                        .param("pageSize", "200"))
            .andExpect(status().isOk());
    }

    // ==================== helpers ====================

    private List<UUID> userTaskIds(String token, String relatesTo) throws Exception {
        var request = get("/user-tasks").header("Authorization", "Bearer " + token).param("pageSize", "200");
        if (relatesTo != null) {
            request = request.param("relatesTo", relatesTo);
        }
        MvcResult result = mockMvc.perform(request)
            .andExpect(status().isOk())
            .andReturn();
        List<UUID> ids = new ArrayList<>();
        for (JsonNode node : mapper.readTree(result.getResponse().getContentAsString()).get("data")) {
            ids.add(UUID.fromString(node.get("id").asText()));
        }
        return ids;
    }

    private UUID instanceOf(UUID taskId) {
        return userTaskRepository.findById(taskId).orElseThrow().getProcessInstanceId();
    }

    private UUID deploy(String key) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/in2-rest-person.bpmn"), StandardCharsets.UTF_8)
            .replace("in2-rest-person", key)
            .replace("Definitions_in2_rest", "Definitions_" + key);
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn(bpmn);
        MvcResult result = mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID processIdOf(String key) {
        return processRepository.findByDefinitionKey(key).orElseThrow().getId();
    }

    private void addMember(UUID userId, String processKey) {
        ProcessEntity process = processRepository.findByDefinitionKey(processKey).orElseThrow();
        ProcessMemberEntity pm = new ProcessMemberEntity();
        pm.setProcessId(process.getId());
        pm.setUserId(userId);
        pm.setRole("OWNER");
        pm.setAddedBy(userId);
        pm.setAddedAt(Instant.now());
        processMemberRepository.save(pm);
    }

    /** Starts an instance and stamps assignee / candidateGroups onto its (open) task row. */
    private UUID task(UUID pdId, String assignee, String candidateGroups) throws Exception {
        StartProcessInstanceDTO startDto = new StartProcessInstanceDTO();
        startDto.setProcessDefinitionId(pdId);
        MvcResult result = mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(startDto))
                        .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();
        UUID piId = UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
        UserTaskEntity row = userTaskRepository.findByProcessInstanceId(piId).stream()
            .filter(t -> t.getCompletedAt() == null)
            .findFirst().orElseThrow();
        row.setAssignee(assignee);
        row.setCandidateGroups(candidateGroups);
        return userTaskRepository.save(row).getId();
    }

    private UiUserEntity createUser(String username) {
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash("secret-in2"));
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