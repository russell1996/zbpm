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
import com.zorrodev.bpm.engine.service.db.UserTaskCandidateWriter;
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
    /** WO-IN-3: писатель кандидатов — фикстура пишет строки тем же кодом, что и движок. */
    @Autowired private UserTaskCandidateWriter userTaskCandidateWriter;
    @Autowired private UserGroupRepository userGroupRepository;
    @Autowired private ProcessRepository processRepository;
    @Autowired private ProcessMemberRepository processMemberRepository;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String adminToken;
    private String aliceToken;
    private String bobToken;
    private String strangerToken;

    private String aliceName;
    private String bobName;
    private String strangerName;
    private String groupName;

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

        // ui_users.username is UNIQUE and the PG database survives between local runs, so the
        // names carry a per-run suffix; the assignee strings on the task rows must match it.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        aliceName = "in2RestAlice-" + suffix;
        bobName = "in2RestBob-" + suffix;
        strangerName = "in2RestStranger-" + suffix;
        aliceId = createUser(aliceName).getId();
        bobId = createUser(bobName).getId();
        strangerId = createUser(strangerName).getId();
        aliceToken = login(aliceName, "secret-in2");
        bobToken = login(bobName, "secret-in2");
        strangerToken = login(strangerName, "secret-in2");

        // alice is a candidate of group "in2RestSales" — the group leg of relatesTo
        groupName = "in2RestSales-" + suffix;
        UserGroupEntity aliceGroup = new UserGroupEntity();
        aliceGroup.setUserId(aliceId);
        aliceGroup.setGroupName(groupName);
        userGroupRepository.save(aliceGroup);

        String visibleKey = "in2-rest-visible-" + suffix;
        String bobOnlyKey = "in2-rest-bob-only-" + suffix;
        String foreignKey = "in2-rest-foreign-" + suffix;
        pdVisible = deploy(visibleKey);
        pdBobOnly = deploy(bobOnlyKey);
        pdForeign = deploy(foreignKey);
        for (String key : List.of(visibleKey, bobOnlyKey, foreignKey)) {
            addMember(aliceId, key);
            addMember(bobId, key);
            addMember(strangerId, key);
        }
        // stranger loses membership in the other two — allowedPdIds must then hide those tasks
        processMemberRepository.deleteAll(processMemberRepository
            .findByProcessIdInAndUserId(
                List.of(processIdOf(bobOnlyKey), processIdOf(foreignKey)), strangerId));

        aliceTaskInVisible = task(pdVisible, aliceName, null);
        aliceTaskViaGroupInVisible = task(pdVisible, bobName, groupName);
        strangerTaskInBobOnly = task(pdBobOnly, strangerName, null);
        strangerTaskInForeign = task(pdForeign, strangerName, null);
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

    // ===== HIGH-1 (red-team, sha 22af7705): the 403 is NOT a person-level boundary =====

    /**
     * Characterization test for the honest formulation CTO demanded on 2026-10-06. It pins what
     * the endpoint actually does, so nobody can read the {@code relatesTo} guard as a security
     * boundary again: Bob is refused the QUESTION about Alice, and then receives exactly the same
     * rows through the PRE-EXISTING {@code assignee} parameter, because {@code assignee} is
     * applied without any authorization of its value (pre-existing on master — V7, not introduced
     * here). Person-level visibility is WO-ACL-23, not this WO.
     *
     * <p>Mutation that must redden this test: drop {@code mayAskAboutUser} in
     * {@code QueryResource.getUserTasks} (the first assertion flips to 200).
     */
    @Test
    void personLevelBoundary_doesNotExist_assigneeHandsOutTheSameRowsRelatesToRefuses() throws Exception {
        mockMvc.perform(get("/user-tasks")
                        .header("Authorization", "Bearer " + bobToken)
                        .param("relatesTo", aliceId.toString()))
            .andExpect(status().isForbidden());

        MvcResult result = mockMvc.perform(get("/user-tasks")
                        .header("Authorization", "Bearer " + bobToken)
                        .param("assignee", aliceName)
                        .param("pageSize", "200"))
            .andExpect(status().isOk())
            .andReturn();

        List<UUID> byAssignee = new ArrayList<>();
        for (JsonNode node : mapper.readTree(result.getResponse().getContentAsString()).get("data")) {
            byAssignee.add(UUID.fromString(node.get("id").asText()));
        }

        // the very rows the guard refused to name, reachable by username
        assertThat(byAssignee).contains(aliceTaskInVisible);
    }

    // ===== the new filters are reachable over HTTP and validated there =====

    /**
     * WO-IN-3: {@code candidateUser} больше не отказ. Тот же вопрос по HTTP, что был отказом
     * E-IN2-1/C0.2 — и ответ теперь настоящий: ровно одна задача, а не «все» и не 400.
     *
     * <p>Мутация, которая обязана покраснить этот тест: вернуть 400 (вариант B) — упадёт первый
     * ассерт; убрать фильтр из {@code findUserTasks} — упадёт второй, потому что строк станет
     * больше одной. Оба варианта «тест всё равно зелёный» здесь закрыты.
     */
    @Test
    void candidateUserParam_overHttp_filtersRows_andNeverFallsBackToEverything() throws Exception {
        UUID aliceCandidateTask = task(pdVisible, null, null, aliceName);
        task(pdVisible, null, null, "in3-rest-nobody-" + UUID.randomUUID());

        mockMvc.perform(get("/user-tasks")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("candidateUser", aliceName)
                        .param("pageSize", "200"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.data[0].id").value(aliceCandidateTask.toString()));
    }

    /** Имя с разделителем списка не представимо — отказ, а не тихая пустая выборка. */
    @Test
    void candidateUserWithDelimiter_overHttp_is400() throws Exception {
        mockMvc.perform(get("/user-tasks")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("candidateUser", "a,b"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("UNSUPPORTED_CANDIDATE_USER_NAME"));
    }

    /** MEDIUM-2 over HTTP: a caller-supplied index must answer an empty page, never a 500. */
    @Test
    void pageIndexMaxValue_overHttp_is200_andNotA500() throws Exception {
        mockMvc.perform(get("/user-tasks")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("pageIndex", String.valueOf(Integer.MAX_VALUE)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void candidateGroupParam_overHttp_filtersRows() throws Exception {
        mockMvc.perform(get("/user-tasks")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("candidateGroup", groupName)
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

    /**
     * Starts an instance and stamps assignee / candidateGroups / candidateUsers onto its (open)
     * task row.
     *
     * <p>WO-IN-3: строки-кандидаты пишет ТОТ ЖЕ рабочий код, что и живой путь
     * ({@code UserTaskCandidateWriter}). Раньше фикстура писала только колонку
     * {@code candidate_groups}, и после перехода читателя на таблицу такие задачи просто перестали
     * бы находиться — то есть тест описывал бы состояние, которого движок создать не может.
     * Стирать перед записью нечего: в {@code in2-rest-person.bpmn} кандидатов нет вовсе.
     */
    private UUID task(UUID pdId, String assignee, String candidateGroups) throws Exception {
        return task(pdId, assignee, candidateGroups, null);
    }

    private UUID task(UUID pdId, String assignee, String candidateGroups, String candidateUsers) throws Exception {
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
        UUID taskId = userTaskRepository.save(row).getId();
        userTaskCandidateWriter.writeCandidates(taskId, candidateGroups, candidateUsers);
        return taskId;
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