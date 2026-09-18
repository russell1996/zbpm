package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-EVT-3: AuthZ integration tests for GET /events.
 * Full-context (V11): real filter chain, real DB, two principals with different grants.
 */
@SpringBootTest(classes = TestMain.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("test")
class EventAuthzIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private PasswordHasher passwordHasher;
    @Autowired private ProcessDefinitionRepository processDefinitionRepository;
    @Autowired private ProcessRepository processRepository;
    @Autowired private DomainEventRepository domainEventRepository;
    @Autowired private com.zorrodev.bpm.engine.scheduler.FeedPositionAssigner feedPositionAssigner;

    private String adminToken;
    private String userTokenA; // has grant on processA only
    private String grantedAKey; // WO-TEST-3 T-01: REAL API-key grant (READ) on processA only
    private String fullAKey;   // WO-SEC-54: API-key with isFull=true grant on processA ONLY
    private String fullAPlusBKey; // WO-SEC-54: isFull=true on processA + limited grant on processB
    private UUID pdIdA;
    private UUID pdIdB;
    private UUID processIdA;
    private UUID processIdB;

    @BeforeAll
    void setup() throws Exception {
        domainEventRepository.deleteAllInBatch();

        // Admin user
        adminToken = login("admin", "admin");

        // User A — will get grant on processA only
        UiUserEntity userA = new UiUserEntity();
        userA.setId(UUID.randomUUID());
        userA.setUsername("evtuser_a_" + UUID.randomUUID());
        userA.setPasswordHash(passwordHasher.hash("pass123"));
        userA.setRole("USER");
        userA.setActive(true);
        userA.setCreatedAt(Instant.now());
        userRepository.save(userA);
        userTokenA = login(userA.getUsername(), "pass123");

        // Two process definitions
        pdIdA = UUID.randomUUID();
        ProcessDefinitionEntity pdA = new ProcessDefinitionEntity();
        pdA.setId(pdIdA);
        pdA.setKey("processA");
        pdA.setName("Process A");
        pdA.setVersion(1);
        pdA.setSha256("sha-a-" + UUID.randomUUID());
        pdA.setCreatedAt(Instant.now());
        processDefinitionRepository.save(pdA);

        pdIdB = UUID.randomUUID();
        ProcessDefinitionEntity pdB = new ProcessDefinitionEntity();
        pdB.setId(pdIdB);
        pdB.setKey("processB");
        pdB.setName("Process B");
        pdB.setVersion(1);
        pdB.setSha256("sha-b-" + UUID.randomUUID());
        pdB.setCreatedAt(Instant.now());
        processDefinitionRepository.save(pdB);

        // Process instances (needed for grant resolution: grants are by processId)
        processIdA = UUID.randomUUID();
        ProcessEntity procA = new ProcessEntity();
        procA.setId(processIdA);
        procA.setDefinitionKey("processA");
        procA.setName("Process A Instance");
        procA.setCreatedAt(Instant.now());
        processRepository.save(procA);

        processIdB = UUID.randomUUID();
        ProcessEntity procB = new ProcessEntity();
        procB.setId(processIdB);
        procB.setDefinitionKey("processB");
        procB.setName("Process B Instance");
        procB.setCreatedAt(Instant.now());
        processRepository.save(procB);

        // WO-SEC-54: API-key principal with a REAL isFull=true grant on processA only.
        // Before the fix, EventAuthzResolver returned null (= see all) for any principal
        // with at least one full grant → this key would see processB events too (S-02).
        UUID userFullAId = createUser("evtuser_fullA_" + UUID.randomUUID());
        addMember(userFullAId, "processA", "OWNER");
        fullAKey = createApiKeyForUser(userFullAId);
        setGrantsFull(userFullAId, "processA");

        // WO-SEC-54 crit #2: isFull=true on processA + limited grant on processB.
        UUID userFullAPlusBId = createUser("evtuser_fullAB_" + UUID.randomUUID());
        addMember(userFullAPlusBId, "processA", "OWNER");
        addMember(userFullAPlusBId, "processB", "VIEWER");
        fullAPlusBKey = createApiKeyForUser(userFullAPlusBId);
        setGrantsRaw(userFullAPlusBId,
            "[{\"processKey\":\"processA\",\"full\":true},"
                + "{\"processKey\":\"processB\",\"permissions\":\"READ\"}]");

        // WO-TEST-3 T-01: a REAL non-full grant on processA only. The old
        // pof_authzIsolation_principalWithGrantOnPdA_doesNotSeePdBEvents asserted "0 events"
        // without ever creating a grant — green under BOTH deny-all AND a global see-all bug
        // (that is how S-02 survived a green CI). This key is created through the real admin
        // API chain (member + api-key + grants) so the isolation test below proves the grant
        // actually WORKS: 2 PD-A events visible, PD-B invisible.
        UUID userGrantedAId = createUser("evtuser_grantA_" + UUID.randomUUID());
        addMember(userGrantedAId, "processA", "OWNER");
        grantedAKey = createApiKeyForUser(userGrantedAId);
        setGrantsRaw(userGrantedAId,
            "[{\"processKey\":\"processA\",\"permissions\":\"READ\"}]");

        // Emit events for both processes
        emitEvent(pdIdA, UUID.randomUUID(), "process-instance.started");
        emitEvent(pdIdA, UUID.randomUUID(), "process-instance.completed");
        emitEvent(pdIdB, UUID.randomUUID(), "process-instance.started");

        // WO-TEST-8: permanent load guard. Two extra events on top of the 3 above break
        // every exact-count assertion on the shared corpus (adminSeesAllEvents == 3,
        // typeFilter == 2, per-process == 2) while all content-based assertions below stay
        // green on this same corpus — that is the RED/GREEN pair of this WO, kept in the
        // test forever so the mines cannot come back. Total stays below the page size.
        emitEvent(pdIdA, UUID.randomUUID(), "process-instance.started");
        emitEvent(pdIdB, UUID.randomUUID(), "process-instance.started");

        // WO-REL-38: курсор ленты — feed_position (ставит джоб), без неё строки
        // невидимы курсору.
        feedPositionAssigner.assignPendingPositions();
    }

    private void emitEvent(UUID processDefinitionId, UUID processInstanceId, String type) {
        DomainEventEntity event = new DomainEventEntity();
        event.setId(UUID.randomUUID());
        event.setType(type);
        event.setVersion(1);
        event.setOccurredAt(Instant.now());
        event.setProcessDefinitionId(processDefinitionId);
        event.setProcessInstanceId(processInstanceId);
        event.setOwnerScope(processDefinitionId.toString());
        event.setData(Map.of());
        domainEventRepository.save(event);
    }

    @SuppressWarnings("unchecked")
    private String login(String username, String password) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        MvcResult result = mockMvc.perform(post("/auth/login")
                .content(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();
        Map<String, Object> body = new com.fasterxml.jackson.databind.ObjectMapper()
            .readValue(result.getResponse().getContentAsString(), Map.class);
        return (String) body.get("token");
    }

    // --- WO-SEC-54 helpers: real API-key grants (V11 full-context, not mocked) ---

    private UUID createUser(String username) {
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash("pass123"));
        user.setFullName(username);
        user.setRole("USER");
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user).getId();
    }

    private void addMember(UUID userId, String processKey, String role) throws Exception {
        mockMvc.perform(post("/processes/" + processKey + "/members")
                .header("Authorization", "Bearer " + adminToken)
                .content("{\"userId\":\"" + userId + "\",\"role\":\"" + role + "\"}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());
    }

    private String createApiKeyForUser(UUID userId) throws Exception {
        MvcResult result = mockMvc.perform(post("/admin/users/" + userId + "/api-key")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isCreated())
            .andReturn();
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readTree(result.getResponse().getContentAsString()).get("key").asText();
    }

    private void setGrantsFull(UUID userId, String processKey) throws Exception {
        setGrantsRaw(userId, "[{\"processKey\":\"" + processKey + "\",\"full\":true}]");
    }

    private void setGrantsRaw(UUID userId, String grantsJson) throws Exception {
        mockMvc.perform(put("/admin/users/" + userId + "/api-key/grants")
                .header("Authorization", "Bearer " + adminToken)
                .content("{\"grants\":" + grantsJson + "}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());
    }

    private Set<String> eventPdIds(String bearer) throws Exception {
        // WO-TEST-8: limit=100 (прод клампит к 100 — EventResource:87) держит видимый
        // корпус целиком на одной странице при росте.
        MvcResult result = mockMvc.perform(get("/events")
                .header("Authorization", "Bearer " + bearer)
                .param("limit", "100"))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode data = new ObjectMapper().readTree(result.getResponse().getContentAsString()).get("data");
        Set<String> pdIds = new HashSet<>();
        data.forEach(e -> pdIds.add(e.get("processDefinitionId").asText()));
        return pdIds;
    }

    // --- Basic access tests ---

    @Test
    void unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/events"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void adminSeesAllEvents() throws Exception {
        mockMvc.perform(get("/events")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].type").isNotEmpty())
            .andExpect(jsonPath("$.data[0].id").isNotEmpty())
            .andExpect(jsonPath("$.data[0].sequence").isNumber());
        // WO-TEST-8: "admin sees events of both processes" means membership, not an exact
        // count — the old `$.data.length() == 3` reddened on any corpus growth.
        assertThat(eventPdIds(adminToken)).contains(pdIdA.toString(), pdIdB.toString());
    }

    @Test
    void userWithNoGrantsSeesNoEvents() throws Exception {
        // WO-TEST-8: the 0 stays, now with an explicit process filter (a grant-less
        // principal sees nothing for ANY corpus size — deny-filtering, not counting).
        mockMvc.perform(get("/events")
                .header("Authorization", "Bearer " + userTokenA)
                .param("processDefinitionKey", "processA"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(0));
    }

    // --- Pagination test ---

    @Test
    void pagination_works() throws Exception {
        mockMvc.perform(get("/events")
                .header("Authorization", "Bearer " + adminToken)
                .param("limit", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].sequence").isNumber())
            .andExpect(jsonPath("$.data[1].sequence").isNumber());
    }

    // --- Type filter test ---

    @Test
    void typeFilter_works() throws Exception {
        // WO-TEST-8: "filter returns only the matching type" means every row matches
        // and both processes are represented — not an exact count (old `== 2`).
        MvcResult result = mockMvc.perform(get("/events")
                .header("Authorization", "Bearer " + adminToken)
                .param("type", "process-instance.started")
                .param("limit", "100"))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode data = new ObjectMapper().readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(data.size()).isGreaterThanOrEqualTo(2);
        Set<String> types = new HashSet<>();
        Set<String> pdIds = new HashSet<>();
        data.forEach(e -> {
            types.add(e.get("type").asText());
            pdIds.add(e.get("processDefinitionId").asText());
        });
        assertThat(types).containsExactly("process-instance.started");
        assertThat(pdIds).contains(pdIdA.toString(), pdIdB.toString());
    }

    // --- WO-SEC-54 (CRITICAL S-02): isFull=true on ONE process must NOT grant see-all ---

    /**
     * POF (WO-SEC-54 crit #1): API-key with a REAL isFull=true grant on processA only
     * must see processA events but NOT processB events.
     * RED (before fix): EventAuthzResolver returned null (= see all) for any principal
     * with at least one full grant → this key sees processB events too.
     * GREEN (after fix): only granted process definition IDs are returned.
     */
    @Test
    void fullGrant_onProcessA_doesNotSeeProcessBEvents() throws Exception {
        Set<String> pdIds = eventPdIds(fullAKey);

        // Sees processA (its own full-grant process)
        assertThat(pdIds).contains(pdIdA.toString());
        // MUST NOT see processB (no grant at all) — this assertion is RED before the fix
        assertThat(pdIds).doesNotContain(pdIdB.toString());
    }

    /**
     * WO-SEC-54 crit #2: isFull=true on processA + limited grant on processB →
     * sees processA AND processB (granted), and nothing else.
     */
    @Test
    void fullPlusLimitedGrants_seesOnlyGrantedProcesses() throws Exception {
        Set<String> pdIds = eventPdIds(fullAPlusBKey);

        assertThat(pdIds).containsExactlyInAnyOrder(pdIdA.toString(), pdIdB.toString());
    }

    /**
     * WO-SEC-54 crit #2 (negative): the same key filtered per processDefinitionKey
     * still works — full access is scoped to the granted processes.
     */
    @Test
    void fullGrant_scopeIsPerProcess_notGlobal() throws Exception {
        // processA (full grant) — its events visible: every row is processA and the
        // setup types are present (WO-TEST-8: old exact `== 2`, breaks on corpus growth).
        MvcResult resultA = mockMvc.perform(get("/events")
                .header("Authorization", "Bearer " + fullAKey)
                .param("processDefinitionKey", "processA")
                .param("limit", "100"))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode dataA = new ObjectMapper().readTree(resultA.getResponse().getContentAsString()).get("data");
        assertThat(dataA.size()).isGreaterThanOrEqualTo(2);
        Set<String> typesA = new HashSet<>();
        dataA.forEach(e -> {
            assertThat(e.get("processDefinitionId").asText()).isEqualTo(pdIdA.toString());
            typesA.add(e.get("type").asText());
        });
        assertThat(typesA).contains("process-instance.started", "process-instance.completed");

        // processB — NO grant → empty for any corpus (grant-scoped, keep the 0).
        mockMvc.perform(get("/events")
                .header("Authorization", "Bearer " + fullAKey)
                .param("processDefinitionKey", "processB"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(0));
    }

    // --- POF: AuthZ isolation (V11 full-context) ---
    // Without authz filter: principal A sees events from PD-B (RED)
    // With authz filter: principal A does NOT see events from PD-B (GREEN)

    /**
     * WO-TEST-3 T-01: replaces the fake-green test that asserted "0 events" for a principal
     * that was never given a grant (green under deny-all AND under a global see-all bug).
     * Here the principal has a REAL grant on processA (created via the admin API chain in
     * setup: member + api-key + grants), so the positive proof is:
     *   - sees its 2 granted PD-A events (asserts the grant actually grants), and
     *   - does NOT see the PD-B event (asserts cross-process isolation).
     * RED if the resolver is bypassed (see-all): the key would see all 3 events.
     */
    @Test
    void principalWithRealGrantOnPdA_seesOwnEvents_notForeignEvents() throws Exception {
        // WO-TEST-8: every visible row belongs to the granted process (old exact `== 2`
        // plus positional data[0]/data[1] — both break on corpus growth).
        MvcResult result = mockMvc.perform(get("/events")
                .header("Authorization", "Bearer " + grantedAKey)
                .param("limit", "100"))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode data = new ObjectMapper().readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(data.size()).isGreaterThanOrEqualTo(2);
        data.forEach(e -> assertThat(e.get("processDefinitionId").asText()).isEqualTo(pdIdA.toString()));

        Set<String> pdIds = eventPdIds(grantedAKey);
        // Positive: the grant really grants access to PD-A events (not just "no error").
        assertThat(pdIds).contains(pdIdA.toString());
        // Isolation: PD-B events must NOT leak through.
        assertThat(pdIds).doesNotContain(pdIdB.toString());
    }

    @Test
    void pof_authzIsolation_processDefinitionKeyFilter_works() throws Exception {
        // Admin can filter by processDefinitionKey (WO-TEST-8: content, not exact counts).
        MvcResult resultA = mockMvc.perform(get("/events")
                .header("Authorization", "Bearer " + adminToken)
                .param("processDefinitionKey", "processA")
                .param("limit", "100"))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode dataA = new ObjectMapper().readTree(resultA.getResponse().getContentAsString()).get("data");
        assertThat(dataA.size()).isGreaterThanOrEqualTo(2);
        Set<String> typesA = new HashSet<>();
        dataA.forEach(e -> {
            assertThat(e.get("processDefinitionId").asText()).isEqualTo(pdIdA.toString());
            typesA.add(e.get("type").asText());
        });
        assertThat(typesA).contains("process-instance.started", "process-instance.completed");

        MvcResult resultB = mockMvc.perform(get("/events")
                .header("Authorization", "Bearer " + adminToken)
                .param("processDefinitionKey", "processB")
                .param("limit", "100"))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode dataB = new ObjectMapper().readTree(resultB.getResponse().getContentAsString()).get("data");
        assertThat(dataB.size()).isGreaterThanOrEqualTo(1);
        dataB.forEach(e -> assertThat(e.get("processDefinitionId").asText()).isEqualTo(pdIdB.toString()));

        // Non-existent key returns empty
        mockMvc.perform(get("/events")
                .header("Authorization", "Bearer " + adminToken)
                .param("processDefinitionKey", "nonExistent"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(0));
    }

    // --- WO-SEC-24 POF: processInstanceId filter ---

    @Test
    void processInstanceIdFilter_onlyReturnsEventsForThatInstance() throws Exception {
        // Emit events for specific process instances
        UUID piId1 = UUID.randomUUID();
        UUID piId2 = UUID.randomUUID();
        emitEvent(pdIdA, piId1, "user-task.created");
        emitEvent(pdIdA, piId2, "user-task.created");
        emitEvent(pdIdA, piId1, "user-task.completed");
        // WO-REL-38: курсор — feed_position (emits выше без позиций).
        feedPositionAssigner.assignPendingPositions();

        // Filter by piId1 — should get only 2 events
        mockMvc.perform(get("/events")
                .header("Authorization", "Bearer " + adminToken)
                .param("processInstanceId", piId1.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].processInstanceId").value(piId1.toString()));

        // Filter by piId2 — should get only 1 event
        mockMvc.perform(get("/events")
                .header("Authorization", "Bearer " + adminToken)
                .param("processInstanceId", piId2.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].processInstanceId").value(piId2.toString()));
    }

    @Test
    void invalidProcessInstanceId_returns400() throws Exception {
        mockMvc.perform(get("/events")
                .header("Authorization", "Bearer " + adminToken)
                .param("processInstanceId", "not-a-uuid"))
            .andExpect(status().isBadRequest());
    }
}
