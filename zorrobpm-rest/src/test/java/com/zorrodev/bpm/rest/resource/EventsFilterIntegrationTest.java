package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.DomainEventEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.DomainEventRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
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

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-INT-7: /events must filter by processDefinitionKey and type IN SQL — for EVERY branch
 * of the query, not only where it accidentally happened via allowedPdIds. Previously the SQL
 * window grabbed the first N events of the WHOLE table and the key/type filter was applied
 * afterwards in memory, so on a live database a keyed request returned EMPTY once enough
 * foreign events accumulated ahead (and the behavior depended on WHO asked — superAdmin got
 * the weak path, members the strong one).
 *
 * Filling is deterministic: 550 foreign events are inserted BEFORE the target ones so the
 * first window can never contain them. Criterion 4 guards the grant narrowing while moving
 * filtering into SQL (the easiest thing to lose).
 */
@SpringBootTest(classes = TestMain.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("test")
class EventsFilterIntegrationTest {

    private static final int FOREIGN_COUNT = 550;

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private PasswordHasher passwordHasher;
    @Autowired private DomainEventRepository domainEventRepository;
    @Autowired private com.zorrodev.bpm.engine.scheduler.FeedPositionAssigner feedPositionAssigner;
    @Autowired private ProcessDefinitionRepository processDefinitionRepository;
    @Autowired private ProcessRepository processRepository;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private final java.util.List<Long> createdEventIds = new java.util.ArrayList<>();

    private String adminToken;
    private String memberToken;
    private UUID targetDefId;
    private UUID foreignDefId;
    private String targetKey;

    @BeforeAll
    void setup() throws Exception {
        adminToken = loginAndGetToken("admin", "admin");

        targetKey = "int7-target-" + UUID.randomUUID().toString().substring(0, 8);
        targetDefId = createDefinition(targetKey);
        foreignDefId = createDefinition("int7-foreign-" + UUID.randomUUID().toString().substring(0, 8));

        // Member of the target process (registry row required by the members API)
        createProcessRow(targetKey);
        UUID memberId = createUser("int7-member-" + UUID.randomUUID().toString().substring(0, 8));
        addMember(memberId, targetKey, "OWNER");
        memberToken = loginAndGetToken(findUsername(memberId), "pass");

        // Deterministic fill: FOREIGN events FIRST (lower sequences), then the target ones.
        // The default window (maxResults+100 <= 200) therefore cannot contain them.
        for (int i = 0; i < FOREIGN_COUNT; i++) {
            emitEvent(foreignDefId, "int7.foreign.event");
        }
        emitEvent(targetDefId, "int7.target.started");
        emitEvent(targetDefId, "int7.target.progress");
        emitEvent(targetDefId, "int7.target.completed");
        // WO-REL-38: курсор ленты — feed_position, которую ставит джоб, а не
        // sequence из save. Без этого все 553 строки невидимы курсору.
        feedPositionAssigner.assignPendingPositions();
    }

    @org.junit.jupiter.api.AfterAll
    void cleanupEvents() {
        // P-59: 553 synthetic events must not leak into the shared H2 for classes running after us
        if (!createdEventIds.isEmpty()) {
            domainEventRepository.deleteAllById(createdEventIds);
        }
    }
    // ==================== Criterion 1 ====================

    @Test
    void criterion1_keyFilter_returnsTargetEvents_despiteForeignWindow() throws Exception {
        JsonNode json = getEvents(adminToken, Map.of("processDefinitionKey", targetKey, "limit", "100"));
        var data = json.get("data");
        assertThat(data.size()).as("keyed request must return the 3 target events").isEqualTo(3);
        for (JsonNode e : data) {
            assertThat(e.get("processDefinitionId").asText()).isEqualTo(targetDefId.toString());
        }
    }

    // ==================== Criterion 2 ====================

    @Test
    void criterion2_keyFilter_sameAnswer_forSuperAdmin_andMember() throws Exception {
        var asAdmin = getEvents(adminToken, Map.of("processDefinitionKey", targetKey, "limit", "100"));
        var asMember = getEvents(memberToken, Map.of("processDefinitionKey", targetKey, "limit", "100"));
        assertThat(asAdmin.get("data").size()).isEqualTo(3);
        assertThat(asMember.get("data").size())
            .as("filter strength must not depend on who asks")
            .isEqualTo(asAdmin.get("data").size());
        for (JsonNode e : asMember.get("data")) {
            assertThat(e.get("processDefinitionId").asText()).isEqualTo(targetDefId.toString());
        }
    }

    // ==================== Criterion 3 ====================

    @Test
    void criterion3_typeFilter_inSql_independentOfWindowSize() throws Exception {
        JsonNode json = getEvents(adminToken, Map.of("type", "int7.target.completed", "limit", "100"));
        var data = json.get("data");
        assertThat(data.size())
            .as("type filter must reach past the foreign window into SQL")
            .isEqualTo(1);
        assertThat(data.get(0).get("type").asText()).isEqualTo("int7.target.completed");
        assertThat(data.get(0).get("processDefinitionId").asText()).isEqualTo(targetDefId.toString());
    }

    // ==================== Criterion 4: grant narrowing survives ====================

    @Test
    void criterion4_member_withoutKey_seesOnlyOwnProcess_events() throws Exception {
        JsonNode json = getEvents(memberToken, Map.of("limit", "100"));
        var data = json.get("data");
        assertThat(data.size()).as("member sees own events").isEqualTo(3);
        for (JsonNode e : data) {
            assertThat(e.get("processDefinitionId").asText())
                .as("no foreign event may leak to a member")
                .isEqualTo(targetDefId.toString());
        }
    }

    @Test
    void criterion4_outsider_seesNoRuntimeEvents() throws Exception {
        UUID outsiderId = createUser("int7-outsider-" + UUID.randomUUID().toString().substring(0, 8));
        String outsiderToken = loginAndGetToken(findUsername(outsiderId), "pass");
        JsonNode json = getEvents(outsiderToken, Map.of("limit", "100"));
        assertThat(json.get("data").size()).as("DENY by default: no membership -> nothing").isZero();
    }

    // ==================== combined key+type ====================

    @Test
    void combined_keyAndType_filterInSql() throws Exception {
        JsonNode json = getEvents(memberToken,
            Map.of("processDefinitionKey", targetKey, "type", "int7.target.started", "limit", "100"));
        assertThat(json.get("data").size()).isEqualTo(1);
        assertThat(json.get("data").get(0).get("type").asText()).isEqualTo("int7.target.started");
    }

    // ==================== helpers ====================

    private JsonNode getEvents(String token, Map<String, String> params) throws Exception {
        var req = get("/events").header("Authorization", "Bearer " + token);
        for (var e : params.entrySet()) req = req.queryParam(e.getKey(), e.getValue());
        MvcResult result = mockMvc.perform(req).andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private UUID createDefinition(String key) {
        ProcessDefinitionEntity def = new ProcessDefinitionEntity();
        def.setId(UUID.randomUUID());
        def.setKey(key);
        def.setName(key);
        def.setVersion(1);
        def.setSha256(UUID.randomUUID().toString());
        def.setCreatedAt(Instant.now());
        return processDefinitionRepository.save(def).getId();
    }

    private void createProcessRow(String definitionKey) {
        ProcessEntity p = new ProcessEntity();
        p.setId(UUID.randomUUID());
        p.setDefinitionKey(definitionKey);
        p.setName(definitionKey);
        p.setCreatedAt(Instant.now());
        processRepository.save(p);
    }

    private void emitEvent(UUID pdId, String type) {
        DomainEventEntity event = new DomainEventEntity();
        event.setId(UUID.randomUUID());
        event.setType(type);
        event.setVersion(1);
        event.setOccurredAt(Instant.now());
        event.setProcessDefinitionId(pdId);
        event.setProcessInstanceId(UUID.randomUUID());
        event.setOwnerScope(pdId.toString());
        event.setData(Map.of());
        domainEventRepository.save(event);
        if (event.getSequence() != null) createdEventIds.add(event.getSequence());
    }

    private String findUsername(UUID userId) {
        return userRepository.findById(userId).orElseThrow().getUsername();
    }

    private UUID createUser(String username) {
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash("pass"));
        user.setFullName(username);
        user.setRole("USER");
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user).getId();
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

    private void addMember(UUID userId, String processKey, String role) throws Exception {
        mockMvc.perform(post("/processes/" + processKey + "/members")
                        .header("Authorization", "Bearer " + adminToken)
                        .content("{\"userId\":\"" + userId + "\",\"role\":\"" + role + "\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }
}
