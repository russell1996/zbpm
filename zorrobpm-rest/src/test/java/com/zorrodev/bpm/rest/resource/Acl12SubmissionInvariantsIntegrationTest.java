package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.dto.ProcessSubmissionDTO;
import com.zorrodev.bpm.contract.dto.RejectSubmissionDTO;
import com.zorrodev.bpm.contract.dto.SubmitProcessSubmissionDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.entity.ProcessSubmissionEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.ProcessSubmissionRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import org.junit.jupiter.api.AfterEach;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-ACL-12 — server half of the submission remarks:
 *  1-3. one PENDING per process key (code check renders a clear 409; the GUARANTEE is the
 *       partial unique index exercised on PostgreSQL in Acl12SubmissionRacePgIT);
 *  5-6. admin queue history with a status filter (default = PENDING, previous behavior);
 *  7-8. stable error codes + params, message preserved; codes distinct and independent of text.
 *
 * Full-context tests (V11) through the real filter chain. Migration dedupe (criterion 4)
 * is proven on PostgreSQL with a verbatim run — see the report.
 *
 * POF (G-K, WO-ACL-12):
 *  1. Remove the PENDING existence check in submit() → criterion1_… goes RED
 *  2. Remove the partial unique index from the migration → Acl12SubmissionRacePgIT goes RED
 *  3. Revert listPending() to no-filter → criterion5_… goes RED
 *  4. Throw plain VALIDATION_ERROR instead of the specific codes → criterion7/criterion8 RED
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Acl12SubmissionInvariantsIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private ProcessRepository processRepository;
    @Autowired private ProcessMemberRepository processMemberRepository;
    @Autowired private ProcessSubmissionRepository submissionRepository;
    @Autowired private PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final List<String> createdKeys = new ArrayList<>();
    private final List<UUID> createdSubmissionIds = new ArrayList<>();
    private String adminToken;
    private UUID adminId;
    private String userToken;
    private UUID userId;

    @BeforeAll
    void setUp() throws Exception {
        adminId = createOrUpdateUser("acl12-admin", "Admin Twelve", "admin12@test.com", "SUPER_ADMIN");
        adminToken = login("acl12-admin");
        userId = createOrUpdateUser("acl12-user", "User Twelve", "user12@test.com", "USER");
        userToken = login("acl12-user");
    }

    @AfterEach
    void tearDown() {
        Collections.reverse(createdSubmissionIds); // newest first — previous_submission_id FK
        submissionRepository.deleteAllById(createdSubmissionIds);
        createdSubmissionIds.clear();
        createdKeys.forEach(key -> {
            processRepository.findByDefinitionKey(key).ifPresent(p -> {
                processMemberRepository.deleteAll(processMemberRepository.findByProcessId(p.getId()));
                processRepository.delete(p);
            });
        });
        createdKeys.clear();
    }

    // === CRITERION 1: one PENDING per key ===

    @Test
    void criterion1_secondPendingSubmissionByKeyRejectedWith409() throws Exception {
        String key = uniqueKey("dup");
        ProcessSubmissionDTO first = submit(userToken, bpmnFor(key));

        MvcResult second = submitRaw(userToken, bpmnFor(key));
        assertEquals(409, second.getResponse().getStatus(),
            "second PENDING for the same key must be 409: " + body(second));
        JsonNode error = readError(second);
        assertEquals("PENDING_SUBMISSION_EXISTS", error.get("code").asText());
        assertEquals(key, error.get("params").get("processKey").asText());
        assertNotNull(error.get("message").asText(), "message must be preserved");
        assertTrue(error.get("message").asText().length() > 0);

        // exactly one PENDING row for the key in the DB
        assertEquals(1, submissionRepository.findAll().stream()
            .filter(s -> s.getProcessKey().equals(key)
                && "PENDING".equals(s.getStatus())).count());
        assertNotNull(first.getId());
    }

    // === CRITERION 3: resubmission after REJECTED / APPROVED ===

    @Test
    void criterion3_resubmitAfterRejectionCreatesNewSubmission() throws Exception {
        String key = uniqueKey("rej");
        ProcessSubmissionDTO rejected = submit(userToken, bpmnFor(key));
        rejectAsAdmin(rejected.getId(), "not needed right now");

        ProcessSubmissionDTO resubmitted = submit(userToken, bpmnFor(key));
        assertNotNull(resubmitted.getId());
        assertNotEquals(rejected.getId(), resubmitted.getId());
        assertEquals(rejected.getId(), resubmitted.getPreviousSubmissionId(),
            "resubmission must chain to the previous submission");
        assertEquals("PENDING", resubmitted.getStatus());
    }

    /**
     * After APPROVAL the process exists in the registry, so a resubmit is rejected by the
     * registry check (ACL-3 behavior) — NOT by the PENDING invariant and NOT with a 500.
     * The invariant itself must not block non-PENDING rows of the same key: a direct PENDING
     * insert for an APPROVED key succeeds (on PostgreSQL this exercises the partial index —
     * see Acl12SubmissionRacePgIT).
     */
    @Test
    void criterion3_resubmitAfterApprovalNotBlockedByPendingInvariant() throws Exception {
        String key = uniqueKey("app");
        ProcessSubmissionDTO approved = submit(userToken, bpmnFor(key));
        approveAsAdmin(approved.getId(), 200);

        MvcResult resubmit = submitRaw(userToken, bpmnFor(key));
        assertEquals(409, resubmit.getResponse().getStatus(),
            "resubmit after approval conflicts on the registry: " + body(resubmit));
        JsonNode error = readError(resubmit);
        assertEquals("PROCESS_ALREADY_EXISTS", error.get("code").asText());
        assertEquals(key, error.get("params").get("processKey").asText());

        // the PENDING invariant does NOT block a PENDING row while an APPROVED row exists
        ProcessSubmissionEntity direct = new ProcessSubmissionEntity();
        direct.setId(UUID.randomUUID());
        direct.setBpmn(bpmnFor(key));
        direct.setProcessKey(key);
        direct.setName("direct");
        direct.setSubmittedBy(userId);
        direct.setSubmittedAt(Instant.now());
        direct.setStatus("PENDING");
        submissionRepository.save(direct);
        createdSubmissionIds.add(direct.getId());
        assertEquals("PENDING", submissionRepository.findById(direct.getId()).orElseThrow().getStatus());
    }

    // === CRITERION 5: status filter, default = PENDING ===

    @Test
    void criterion5_statusFilterEachStatusAndDefaultPending() throws Exception {
        String keyRejected = uniqueKey("f-rej");
        ProcessSubmissionDTO rejected = submit(userToken, bpmnFor(keyRejected));
        rejectAsAdmin(rejected.getId(), "no");

        String keyApproved = uniqueKey("f-app");
        ProcessSubmissionDTO approved = submit(userToken, bpmnFor(keyApproved));
        approveAsAdmin(approved.getId(), 200);

        String keyPending = uniqueKey("f-pen");
        ProcessSubmissionDTO pending = submit(userToken, bpmnFor(keyPending));

        String supKey = uniqueKey("f-sup");
        ProcessSubmissionEntity superseded = new ProcessSubmissionEntity();
        superseded.setId(UUID.randomUUID());
        superseded.setBpmn(bpmnFor(supKey));
        superseded.setProcessKey(supKey);
        superseded.setName("superseded");
        superseded.setSubmittedBy(userId);
        superseded.setSubmittedAt(Instant.now());
        superseded.setStatus("SUPERSEDED");
        submissionRepository.save(superseded);
        createdSubmissionIds.add(superseded.getId());

        // no parameter → previous behavior: PENDING only
        List<ProcessSubmissionDTO> defaultQueue = listAsAdmin(null);
        assertTrue(defaultQueue.stream().noneMatch(s -> s.getId().equals(rejected.getId())),
            "default queue must not contain REJECTED");
        assertTrue(defaultQueue.stream().noneMatch(s -> s.getId().equals(approved.getId())),
            "default queue must not contain APPROVED");
        assertTrue(defaultQueue.stream().anyMatch(s -> s.getId().equals(pending.getId())),
            "default queue must contain the PENDING submission");

        List<ProcessSubmissionDTO> onlyPending = listAsAdmin("PENDING");
        assertEquals(1, onlyPending.size());
        assertEquals(pending.getId(), onlyPending.get(0).getId());

        List<ProcessSubmissionDTO> onlyApproved = listAsAdmin("APPROVED");
        assertEquals(1, onlyApproved.size());
        assertEquals(approved.getId(), onlyApproved.get(0).getId());

        List<ProcessSubmissionDTO> onlyRejected = listAsAdmin("REJECTED");
        assertEquals(1, onlyRejected.size());
        assertEquals(rejected.getId(), onlyRejected.get(0).getId());

        List<ProcessSubmissionDTO> onlySuperseded = listAsAdmin("SUPERSEDED");
        assertEquals(1, onlySuperseded.size());
        assertEquals(superseded.getId(), onlySuperseded.get(0).getId());

        List<ProcessSubmissionDTO> all = listAsAdmin("ALL");
        assertEquals(4, all.size());

        // unknown filter → 400, not 500
        mockMvc.perform(get("/process-submissions")
                .param("status", "garbage")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isBadRequest());
    }

    // === CRITERION 6: reviewed history with who/when ===

    @Test
    void criterion6_reviewedHistoryVisibleWithReviewedByUsernameAndReviewedAt() throws Exception {
        String keyApp = uniqueKey("h-app");
        ProcessSubmissionDTO approved = submit(userToken, bpmnFor(keyApp));
        approveAsAdmin(approved.getId(), 200);

        String keyRej = uniqueKey("h-rej");
        ProcessSubmissionDTO rejected = submit(userToken, bpmnFor(keyRej));
        rejectAsAdmin(rejected.getId(), "policy");

        List<ProcessSubmissionDTO> history = listAsAdmin("ALL");
        ProcessSubmissionDTO app = history.stream().filter(s -> s.getId().equals(approved.getId()))
            .findFirst().orElseThrow();
        assertEquals("APPROVED", app.getStatus());
        assertEquals("acl12-admin", app.getReviewedByUsername());
        assertNotNull(app.getReviewedAt(), "reviewedAt must be set on approval");
        assertNull(app.getRejectReason());

        ProcessSubmissionDTO rej = history.stream().filter(s -> s.getId().equals(rejected.getId()))
            .findFirst().orElseThrow();
        assertEquals("REJECTED", rej.getStatus());
        assertEquals("acl12-admin", rej.getReviewedByUsername());
        assertNotNull(rej.getReviewedAt(), "reviewedAt must be set on rejection");
        assertEquals("policy", rej.getRejectReason());
    }

    // === CRITERION 7: one stable code + params per error class ===

    @Test
    void criterion7a_invalidKeyCodeAndParams() throws Exception {
        MvcResult result = submitRaw(userToken, bpmnFor("ab"));
        assertError(result, 400, "INVALID_PROCESS_KEY", "key", "ab");
    }

    @Test
    void criterion7b_tooLargeCodeAndParams() throws Exception {
        String oversized = bpmnFor(uniqueKey("big")) + "<!--" + "x".repeat(300_000) + "-->";
        MvcResult result = submitRaw(userToken, oversized);
        assertError(result, 400, "BPMN_TOO_LARGE", null, null);
        JsonNode error = readError(result);
        assertEquals(262_144, error.get("params").get("maxLength").asInt());
        assertTrue(error.get("params").get("actualLength").asInt() > 262_144);
    }

    @Test
    void criterion7c_missingSubmissionCodeAndParams() throws Exception {
        UUID missing = UUID.randomUUID();
        MvcResult approve = mockMvc.perform(post("/process-submissions/" + missing + "/approve")
                .header("Authorization", "Bearer " + adminToken))
            .andReturn();
        assertError(approve, 404, "SUBMISSION_NOT_FOUND", "submissionId", missing.toString());

        MvcResult bpmn = mockMvc.perform(get("/process-submissions/" + missing + "/bpmn")
                .header("Authorization", "Bearer " + adminToken))
            .andReturn();
        assertError(bpmn, 404, "SUBMISSION_NOT_FOUND", "submissionId", missing.toString());
    }

    @Test
    void criterion7d_alreadyReviewedCodeAndParams() throws Exception {
        String key = uniqueKey("rev");
        ProcessSubmissionDTO rejected = submit(userToken, bpmnFor(key));
        rejectAsAdmin(rejected.getId(), "no");
        MvcResult approveAgain = mockMvc.perform(post("/process-submissions/" + rejected.getId() + "/approve")
                .header("Authorization", "Bearer " + adminToken))
            .andReturn();
        assertError(approveAgain, 409, "SUBMISSION_ALREADY_REVIEWED",
            "submissionId", rejected.getId().toString());
        JsonNode error = readError(approveAgain);
        assertEquals("REJECTED", error.get("params").get("status").asText());

        String key2 = uniqueKey("rev2");
        ProcessSubmissionDTO approved = submit(userToken, bpmnFor(key2));
        approveAsAdmin(approved.getId(), 200);
        MvcResult rejectAgain = mockMvc.perform(post("/process-submissions/" + approved.getId() + "/reject")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"late\"}"))
            .andReturn();
        assertError(rejectAgain, 409, "SUBMISSION_ALREADY_REVIEWED",
            "submissionId", approved.getId().toString());
        assertEquals("APPROVED", readError(rejectAgain).get("params").get("status").asText());
    }

    @Test
    void criterion7e_processKeyMismatchCodeAndParams() throws Exception {
        String targetKey = uniqueKey("tgt");
        ProcessDefinition deployed = deployAsAdmin(targetKey);
        String wrongKey = uniqueKey("wrong");

        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn(bpmnFor(wrongKey));
        MvcResult result = mockMvc.perform(post("/process-definitions/" + deployed.getId() + "/versions")
                .header("Authorization", "Bearer " + adminToken)
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andReturn();
        assertError(result, 400, "PROCESS_KEY_MISMATCH", "xmlKey", wrongKey);
        JsonNode error = readError(result);
        assertEquals(targetKey, error.get("params").get("targetKey").asText());
    }

    @Test
    void criterion7f_registryConflictCodeAndParams() throws Exception {
        String key = uniqueKey("reg");
        deployAsAdmin(key); // registry now holds the key — submit conflicts
        MvcResult result = submitRaw(userToken, bpmnFor(key));
        assertError(result, 409, "PROCESS_ALREADY_EXISTS", "processKey", key);
    }

    // === CRITERION 8: codes distinct and independent of the message text ===

    @Test
    void criterion8_codesDistinctAcrossThePath() throws Exception {
        Set<String> codes = new HashSet<>();

        // PENDING_SUBMISSION_EXISTS
        String key = uniqueKey("d1");
        submit(userToken, bpmnFor(key));
        codes.add(readError(submitRaw(userToken, bpmnFor(key))).get("code").asText());

        // INVALID_PROCESS_KEY
        codes.add(readError(submitRaw(userToken, bpmnFor("ab"))).get("code").asText());

        // BPMN_TOO_LARGE
        codes.add(readError(submitRaw(userToken,
            bpmnFor(uniqueKey("d2")) + "<!--" + "x".repeat(300_000) + "-->")).get("code").asText());

        // SUBMISSION_NOT_FOUND
        codes.add(readError(mockMvc.perform(post("/process-submissions/" + UUID.randomUUID() + "/approve")
            .header("Authorization", "Bearer " + adminToken)).andReturn()).get("code").asText());

        // SUBMISSION_ALREADY_REVIEWED
        String key2 = uniqueKey("d3");
        ProcessSubmissionDTO rej = submit(userToken, bpmnFor(key2));
        rejectAsAdmin(rej.getId(), "no");
        codes.add(readError(mockMvc.perform(post("/process-submissions/" + rej.getId() + "/approve")
            .header("Authorization", "Bearer " + adminToken)).andReturn()).get("code").asText());

        // PROCESS_KEY_MISMATCH
        String targetKey = uniqueKey("d4");
        ProcessDefinition deployed = deployAsAdmin(targetKey);
        AddProcessDefinitionDTO mismatch = new AddProcessDefinitionDTO();
        mismatch.setBpmn(bpmnFor(uniqueKey("d5")));
        codes.add(readError(mockMvc.perform(post("/process-definitions/" + deployed.getId() + "/versions")
            .header("Authorization", "Bearer " + adminToken)
            .content(mapper.writeValueAsString(mismatch))
            .contentType(MediaType.APPLICATION_JSON)).andReturn()).get("code").asText());

        // PROCESS_ALREADY_EXISTS
        submit(userToken, bpmnFor(uniqueKey("d6")));
        codes.add(readError(submitRaw(userToken, bpmnFor(targetKey))).get("code").asText());

        assertEquals(7, codes.size(),
            "every error class must have its own code, no overlaps: " + codes);
        assertTrue(codes.containsAll(Set.of("PENDING_SUBMISSION_EXISTS", "INVALID_PROCESS_KEY",
            "BPMN_TOO_LARGE", "SUBMISSION_NOT_FOUND", "SUBMISSION_ALREADY_REVIEWED",
            "PROCESS_KEY_MISMATCH", "PROCESS_ALREADY_EXISTS")));
    }

    @Test
    void criterion8_codeStableWhenMessageTextChanges() throws Exception {
        // Two different keys → two different message texts → the SAME code: the code is an
        // explicit literal, not derived from the message (the frontend can rely on it).
        MvcResult first = submitRaw(userToken, bpmnFor("ab"));
        MvcResult second = submitRaw(userToken, bpmnFor("cd"));
        assertEquals("INVALID_PROCESS_KEY", readError(first).get("code").asText());
        assertEquals("INVALID_PROCESS_KEY", readError(second).get("code").asText());
        assertNotEquals(readError(first).get("message").asText(),
            readError(second).get("message").asText(),
            "the message texts must differ — the code must not follow them");
        assertEquals("ab", readError(first).get("params").get("key").asText());
        assertEquals("cd", readError(second).get("params").get("key").asText());
    }

    // === Helpers ===

    private UUID createOrUpdateUser(String username, String fullName, String email, String role) {
        UiUserEntity existing = userRepository.findByUsername(username).orElse(null);
        if (existing != null) return existing.getId();

        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash("pass"));
        user.setFullName(fullName);
        user.setEmail(email);
        user.setRole(role);
        user.setActive(true);
        user.setForcePasswordChange(false);
        user.setCreatedAt(java.time.Instant.now());
        user.setUpdatedAt(java.time.Instant.now());
        return userRepository.save(user).getId();
    }

    private String login(String username) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword("pass");
        String result = mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(dto)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return mapper.readTree(result).get("token").asText();
    }

    private ProcessSubmissionDTO submit(String token, String bpmnXml) throws Exception {
        MvcResult result = submitRaw(token, bpmnXml);
        assertEquals(201, result.getResponse().getStatus(), "submit must succeed: " + body(result));
        ProcessSubmissionDTO dto = mapper.readValue(result.getResponse().getContentAsString(),
            ProcessSubmissionDTO.class);
        createdSubmissionIds.add(dto.getId());
        return dto;
    }

    private MvcResult submitRaw(String token, String bpmnXml) throws Exception {
        SubmitProcessSubmissionDTO dto = new SubmitProcessSubmissionDTO();
        dto.setBpmn(bpmnXml);
        return mockMvc.perform(post("/process-submissions")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(dto)))
            .andReturn();
    }

    private ProcessSubmissionDTO approveAsAdmin(UUID submissionId, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/process-submissions/" + submissionId + "/approve")
                .header("Authorization", "Bearer " + adminToken))
            .andReturn();
        assertEquals(expectedStatus, result.getResponse().getStatus(), "approve: " + body(result));
        if (expectedStatus != 200) return null;
        ProcessSubmissionDTO dto = mapper.readValue(result.getResponse().getContentAsString(),
            ProcessSubmissionDTO.class);
        createdKeys.add(dto.getProcessKey());
        return dto;
    }

    private void rejectAsAdmin(UUID submissionId, String reason) throws Exception {
        RejectSubmissionDTO dto = new RejectSubmissionDTO();
        dto.setReason(reason);
        MvcResult result = mockMvc.perform(post("/process-submissions/" + submissionId + "/reject")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(dto)))
            .andReturn();
        assertEquals(200, result.getResponse().getStatus(), "reject: " + body(result));
    }

    private ProcessDefinition deployAsAdmin(String key) throws Exception {
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn(bpmnFor(key));
        String result = mockMvc.perform(post("/process-definitions")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(dto)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        createdKeys.add(key);
        return mapper.readValue(result, ProcessDefinition.class);
    }

    private List<ProcessSubmissionDTO> listAsAdmin(String statusParam) throws Exception {
        var request = get("/process-submissions")
            .header("Authorization", "Bearer " + adminToken);
        if (statusParam != null) {
            request = request.param("status", statusParam);
        }
        String result = mockMvc.perform(request)
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return List.of(mapper.readValue(result, ProcessSubmissionDTO[].class));
    }

    private void assertError(MvcResult result, int expectedStatus, String expectedCode,
                             String paramName, String paramValue) throws Exception {
        assertEquals(expectedStatus, result.getResponse().getStatus(),
            "expected HTTP " + expectedStatus + " with code " + expectedCode + ": " + body(result));
        JsonNode error = readError(result);
        assertEquals(expectedCode, error.get("code").asText());
        assertTrue(error.hasNonNull("params"), "params field must be present: " + body(result));
        assertTrue(error.hasNonNull("message") && error.get("message").asText().length() > 0,
            "message must be preserved: " + body(result));
        if (paramName != null) {
            assertEquals(paramValue, error.get("params").get(paramName).asText());
        }
    }

    private JsonNode readError(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private String bpmnFor(String key) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:bpmndi=\"http://www.omg.org/spec/BPMN/20100524/DI\""
            + " xmlns:dc=\"http://www.omg.org/spec/DD/20100524/DC\""
            + " xmlns:di=\"http://www.omg.org/spec/DD/20100524/DI\""
            + " id=\"Definitions_1\" targetNamespace=\"http://bpmn.io/schema/bpmn\">"
            + "  <bpmn:process id=\"" + key + "\" name=\"Test Process\" isExecutable=\"true\">"
            + "    <bpmn:startEvent id=\"startEvent\">"
            + "      <bpmn:outgoing>flow1</bpmn:outgoing>"
            + "    </bpmn:startEvent>"
            + "    <bpmn:endEvent id=\"endEvent\">"
            + "      <bpmn:incoming>flow1</bpmn:incoming>"
            + "    </bpmn:endEvent>"
            + "    <bpmn:sequenceFlow id=\"flow1\" sourceRef=\"startEvent\" targetRef=\"endEvent\" />"
            + "  </bpmn:process>"
            + "  <bpmndi:BPMNDiagram id=\"BPMNDiagram_1\">"
            + "    <bpmndi:BPMNPlane id=\"BPMNPlane_1\" bpmnElement=\"" + key + "\">"
            + "      <bpmndi:BPMNShape id=\"StartEvent_1_di\" bpmnElement=\"startEvent\">"
            + "        <dc:Bounds x=\"162\" y=\"82\" width=\"36\" height=\"36\" />"
            + "      </bpmndi:BPMNShape>"
            + "      <bpmndi:BPMNShape id=\"Event_1_di\" bpmnElement=\"endEvent\">"
            + "        <dc:Bounds x=\"432\" y=\"82\" width=\"36\" height=\"36\" />"
            + "      </bpmndi:BPMNShape>"
            + "      <bpmndi:BPMNEdge id=\"Flow_1_di\" bpmnElement=\"flow1\">"
            + "        <di:waypoint x=\"198\" y=\"100\" />"
            + "        <di:waypoint x=\"432\" y=\"100\" />"
            + "      </bpmndi:BPMNEdge>"
            + "    </bpmndi:BPMNPlane>"
            + "  </bpmndi:BPMNDiagram>"
            + "</bpmn:definitions>";
    }

    private String uniqueKey(String prefix) {
        String key = prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
        createdKeys.add(key);
        return key;
    }
}