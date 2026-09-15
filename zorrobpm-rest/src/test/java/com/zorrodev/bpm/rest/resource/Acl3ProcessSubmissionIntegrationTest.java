package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.ProcessRole;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.dto.ProcessSubmissionDTO;
import com.zorrodev.bpm.contract.dto.RejectSubmissionDTO;
import com.zorrodev.bpm.contract.dto.SubmitProcessSubmissionDTO;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.entity.ProcessSubmissionEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.ProcessSubmissionRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.retention.RetentionBatchProcessor;
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

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-ACL-3 (ADR-8 п.3): process submissions — user submits BPMN for a NEW process, SUPER_ADMIN
 * approves (submitter becomes OWNER) or rejects with a reason. Full-context tests (V11) through
 * the real filter chain.
 *
 * POF (G-K): criterion4 — atomic approval. The mutation removes @Transactional from
 * ProcessSubmissionServiceImpl.approve and throws after the version was created: the version
 * commits in its own transaction (addProcessDefinition joins REQUIRED) and the test proves that
 * NO half (orphaned version / ownerless process) may remain — that guarantee is exactly the
 * single-transaction deliverable.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Acl3ProcessSubmissionIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private ProcessRepository processRepository;
    @Autowired private ProcessMemberRepository processMemberRepository;
    @Autowired private ProcessDefinitionRepository processDefinitionRepository;
    @Autowired private ProcessSubmissionRepository submissionRepository;
    @Autowired private RetentionBatchProcessor retentionBatchProcessor;
    @Autowired private PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final List<String> createdKeys = new ArrayList<>();
    private final List<UUID> createdSubmissionIds = new ArrayList<>();
    private String adminToken;
    private UUID adminId;
    private String userToken;
    private UUID userId;
    private String bpmn;

    @BeforeAll
    void setup() throws Exception {
        bpmn = new String(Files.readAllBytes(Paths.get("src/test/files/process1.bpmn")));

        adminId = createUser("acl3-admin", "SUPER_ADMIN");
        userId = createUser("acl3-user", "USER");

        adminToken = login("acl3-admin", "pass");
        userToken = login("acl3-user", "pass");
    }

    private UUID createUser(String username, String globalRole) {
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash("pass"));
        user.setFullName("ACL3 " + username);
        user.setRole(globalRole);
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
        return user.getId();
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

    private String uniqueKey() {
        return "acl3_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private String bpmnFor(String key) {
        return bpmn.replace("id=\"process1\"", "id=\"" + key + "\"")
                .replace("name=\"Process 1\"", "name=\"" + key + "\"");
    }

    private MvcResult submitRaw(String token, String bpmnXml) throws Exception {
        SubmitProcessSubmissionDTO dto = new SubmitProcessSubmissionDTO();
        dto.setBpmn(bpmnXml);
        return mockMvc.perform(post("/process-submissions")
                        .header("Authorization", "Bearer " + token)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();
    }

    private ProcessSubmissionDTO submitAndExpect(String token, String bpmnXml, int expectedStatus) throws Exception {
        MvcResult result = submitRaw(token, bpmnXml);
        assertEquals(expectedStatus, result.getResponse().getStatus(),
            "submit status: " + result.getResponse().getContentAsString());
        ProcessSubmissionDTO dto = mapper.readValue(result.getResponse().getContentAsString(), ProcessSubmissionDTO.class);
        createdSubmissionIds.add(dto.getId());
        return dto;
    }

    private ProcessSubmissionDTO approveAsAdmin(UUID submissionId, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/process-submissions/" + submissionId + "/approve")
                        .header("Authorization", "Bearer " + adminToken))
                .andReturn();
        assertEquals(expectedStatus, result.getResponse().getStatus(),
            "approve status: " + result.getResponse().getContentAsString());
        return mapper.readValue(result.getResponse().getContentAsString(), ProcessSubmissionDTO.class);
    }

    private ProcessSubmissionDTO rejectAsAdmin(UUID submissionId, String reason, int expectedStatus) throws Exception {
        RejectSubmissionDTO dto = new RejectSubmissionDTO();
        dto.setReason(reason);
        MvcResult result = mockMvc.perform(post("/process-submissions/" + submissionId + "/reject")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();
        assertEquals(expectedStatus, result.getResponse().getStatus(),
            "reject status: " + result.getResponse().getContentAsString());
        if (expectedStatus != 200) {
            return null; // error body is {"message": ...}, not a ProcessSubmissionDTO
        }
        return mapper.readValue(result.getResponse().getContentAsString(), ProcessSubmissionDTO.class);
    }

    private void deployAsAdmin(String key) throws Exception {
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn(bpmnFor(key));
        mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());
        createdKeys.add(key);
    }

    private List<ProcessDefinitionEntity> definitionsByKey(String key) {
        return processDefinitionRepository.findAll(
            (root, q, cb) -> cb.equal(root.get("key"), key));
    }

    /**
     * Tests share one Spring context and one in-memory DB. Clean up what THIS class created:
     * submissions first (approved_definition_id FK), then members/process/definitions, so the
     * shared state returns to the pre-class shape (P-8).
     */
    @AfterEach
    void cleanup() {
        Collections.reverse(createdSubmissionIds); // newest first — previous_submission_id FK
        submissionRepository.deleteAllById(createdSubmissionIds);
        createdSubmissionIds.clear();
        for (String key : createdKeys) {
            processRepository.findByDefinitionKey(key).ifPresent(p -> {
                processMemberRepository.findByProcessId(p.getId()).forEach(processMemberRepository::delete);
                processRepository.delete(p);
            });
            processDefinitionRepository.deleteAll(definitionsByKey(key));
        }
        createdKeys.clear();
    }

    // ---------------------------------------------------------------- criteria 1, 4, 5, 7

    @Test
    void criterion1_submissionNotVisibleInGeneralLists() throws Exception {
        String key = uniqueKey();
        ProcessSubmissionDTO dto = submitAndExpect(userToken, bpmnFor(key), 201);
        assertEquals("PENDING", dto.getStatus());
        assertEquals(key, dto.getProcessKey());
        assertEquals(userId, dto.getSubmittedBy());

        // NOT in the process registry and NOT in the deployed definitions
        assertTrue(processRepository.findByDefinitionKey(key).isEmpty());
        assertTrue(definitionsByKey(key).isEmpty());
        // ... and NOT in the general definitions list
        mockMvc.perform(get("/process-definitions")
                        .param("processDefinitionKey", key)
                        .param("pageSize", "200")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void criterion2_unparsableBpmnRejectedAtSubmit() throws Exception {
        MvcResult result = submitRaw(userToken, "<not-a-bpmn>");
        assertEquals(400, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentAsString().contains("parsed"),
            "rejection must be actionable: " + result.getResponse().getContentAsString());
    }

    @Test
    void criterion2b_keyViolatingNamingConventionRejectedAtSubmit() throws Exception {
        MvcResult result = submitRaw(userToken, bpmnFor("Bad.Key_With.Caps"));
        assertEquals(400, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentAsString().contains("naming convention"),
            "convention must be explained: " + result.getResponse().getContentAsString());
    }

    @Test
    void criterion2c_oversizedBpmnRejectedAtSubmit() throws Exception {
        String oversized = bpmnFor(uniqueKey()) + "<!--" + "x".repeat(300_000) + "-->";
        MvcResult result = submitRaw(userToken, oversized);
        assertEquals(400, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentAsString().contains("256 KB"),
            "size limit must be explained: " + result.getResponse().getContentAsString());
    }

    @Test
    void criterion3_existingRegistryKeyRejectedAtSubmit() throws Exception {
        String key = uniqueKey();
        deployAsAdmin(key);
        MvcResult result = submitRaw(userToken, bpmnFor(key));
        assertEquals(409, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentAsString().contains("update"),
            "must explain the update path: " + result.getResponse().getContentAsString());
    }

    @Test
    void criterion4_approvalCreatesVersionProcessAndOwnerAtomically() throws Exception {
        String key = uniqueKey();
        ProcessSubmissionDTO submission = submitAndExpect(userToken, bpmnFor(key), 201);

        approveAsAdmin(submission.getId(), 200);

        // version created (deployed, active)
        List<ProcessDefinitionEntity> defs = definitionsByKey(key);
        assertEquals(1, defs.size());
        assertEquals(1, defs.get(0).getVersion());
        // registry process created
        ProcessEntity process = processRepository.findByDefinitionKey(key).orElseThrow();
        // submitter became OWNER — not the admin who approved
        List<ProcessMemberEntity> members = processMemberRepository.findByProcessId(process.getId());
        assertEquals(1, members.size());
        assertEquals(ProcessRole.OWNER.name(), members.get(0).getRole());
        assertEquals(userId, members.get(0).getUserId());
        // submission terminal + links the approved definition
        ProcessSubmissionEntity stored = submissionRepository.findById(submission.getId()).orElseThrow();
        assertEquals("APPROVED", stored.getStatus());
        assertEquals(adminId, stored.getReviewedBy());
        assertEquals(defs.get(0).getId(), stored.getApprovedDefinitionId());
    }

    /**
     * POF (G-K): atomic approval. With the mutation (no @Transactional on approve + throw
     * after the version was created) the version commits alone (addProcessDefinition joins
     * REQUIRED) and this test proves that half must not remain — RED on the mutation, GREEN
     * on the clean single-transaction code.
     */
    @Test
    void criterion4b_atomicFailureLeavesNoHalf() throws Exception {
        String key = uniqueKey();
        ProcessSubmissionDTO submission = submitAndExpect(userToken, bpmnFor(key), 201);

        MvcResult result = mockMvc.perform(post("/process-submissions/" + submission.getId() + "/approve")
                        .header("Authorization", "Bearer " + adminToken))
                .andReturn();

        if (result.getResponse().getStatus() == 500) {
            // failure mode (POF mutation): NO half may remain — no orphaned version,
            // no ownerless process, submission stays PENDING (retryable)
            assertTrue(definitionsByKey(key).isEmpty(), "orphaned version must not remain");
            assertTrue(processRepository.findByDefinitionKey(key).isEmpty(), "ownerless process must not remain");
            assertEquals("PENDING", submissionRepository.findById(submission.getId()).orElseThrow().getStatus());
        } else {
            // success mode (clean code): version + process + OWNER all created atomically
            assertEquals(200, result.getResponse().getStatus());
            assertEquals(1, definitionsByKey(key).size());
            assertTrue(processRepository.findByDefinitionKey(key).isPresent());
            assertEquals("APPROVED", submissionRepository.findById(submission.getId()).orElseThrow().getStatus());
        }
    }

    @Test
    void criterion5_onlySuperAdminCanApproveAndReject() throws Exception {
        String key = uniqueKey();
        ProcessSubmissionDTO submission = submitAndExpect(userToken, bpmnFor(key), 201);

        mockMvc.perform(post("/process-submissions/" + submission.getId() + "/approve")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        RejectSubmissionDTO reject = new RejectSubmissionDTO();
        reject.setReason("nope");
        mockMvc.perform(post("/process-submissions/" + submission.getId() + "/reject")
                        .header("Authorization", "Bearer " + userToken)
                        .content(mapper.writeValueAsString(reject))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
        // review queue is admin-only too
        mockMvc.perform(get("/process-submissions")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        // still PENDING — the 403s did not mutate it
        assertEquals("PENDING", submissionRepository.findById(submission.getId()).orElseThrow().getStatus());
    }

    @Test
    void criterion6_rejectWithoutReasonFails() throws Exception {
        String key = uniqueKey();
        ProcessSubmissionDTO submission = submitAndExpect(userToken, bpmnFor(key), 201);
        rejectAsAdmin(submission.getId(), "   ", 400);
        rejectAsAdmin(submission.getId(), null, 400);
        assertEquals("PENDING", submissionRepository.findById(submission.getId()).orElseThrow().getStatus());
    }

    @Test
    void criterion6b_rejectedSubmissionStaysVisibleToAuthorWithReason() throws Exception {
        String key = uniqueKey();
        ProcessSubmissionDTO submission = submitAndExpect(userToken, bpmnFor(key), 201);
        rejectAsAdmin(submission.getId(), "Company policy forbids this process", 200);

        MvcResult mine = mockMvc.perform(get("/process-submissions/mine")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andReturn();
        List<ProcessSubmissionDTO> mineList = mapper.readValue(
            mine.getResponse().getContentAsString(),
            mapper.getTypeFactory().constructCollectionType(List.class, ProcessSubmissionDTO.class));
        ProcessSubmissionDTO visible = mineList.stream()
            .filter(s -> s.getId().equals(submission.getId()))
            .findFirst().orElseThrow();
        assertEquals("REJECTED", visible.getStatus());
        assertEquals("Company policy forbids this process", visible.getRejectReason());
    }

    /**
     * WO-ACL-12 rewrote this scenario: two PENDING submissions for the same key can no
     * longer exist (invariant + partial unique index), so the "second approval of the same
     * key" path is unreachable through the API. The test now asserts the NEW contract:
     * the second submit is a clear 409, approval of the first works, a resubmit after
     * approval is rejected at submit time (registry, not a 500), and re-approving an
     * already-approved submission is a 409 (not a 500).
     */
    @Test
    void criterion7_secondPendingSubmissionConflictsNot500() throws Exception {
        String key = uniqueKey();
        String bpmnXml = bpmnFor(key);
        ProcessSubmissionDTO first = submitAndExpect(userToken, bpmnXml, 201);

        // WO-ACL-12 criterion 1: second PENDING for the same key is rejected with 409
        MvcResult second = submitRaw(userToken, bpmnXml);
        assertEquals(409, second.getResponse().getStatus(),
            "second PENDING for the same key must conflict: " + second.getResponse().getContentAsString());

        approveAsAdmin(first.getId(), 200);

        // and a NEW submission for the same key is now rejected at submit time (registry)
        MvcResult resubmit = submitRaw(userToken, bpmnXml);
        assertEquals(409, resubmit.getResponse().getStatus(),
            "resubmit after approval must conflict on the registry: " + resubmit.getResponse().getContentAsString());

        // re-approving an already-approved submission is a 409, not a 500
        MvcResult reapprove = mockMvc.perform(post("/process-submissions/" + first.getId() + "/approve")
                        .header("Authorization", "Bearer " + adminToken))
                .andReturn();
        assertEquals(409, reapprove.getResponse().getStatus(),
            "re-approving an approved submission must conflict: " + reapprove.getResponse().getContentAsString());
    }

    @Test
    void criterion8_retentionDeletesTerminalSubmissions() throws Exception {
        String key = uniqueKey();
        ProcessSubmissionDTO approved = submitAndExpect(userToken, bpmnFor(key), 201);
        approveAsAdmin(approved.getId(), 200);

        // PENDING submission must NOT be eligible
        String pendingKey = uniqueKey();
        ProcessSubmissionDTO pending = submitAndExpect(userToken, bpmnFor(pendingKey), 201);

        List<UUID> eligible = retentionBatchProcessor.findEligibleSubmissions(Instant.now(), 10);
        assertTrue(eligible.contains(approved.getId()), "APPROVED submission must be eligible");
        assertTrue(!eligible.contains(pending.getId()), "PENDING submission must not be eligible");

        assertEquals(1, retentionBatchProcessor.deleteSubmission(approved.getId()));
        assertEquals(0, retentionBatchProcessor.deleteSubmission(approved.getId())); // idempotent
        assertTrue(submissionRepository.findById(approved.getId()).isEmpty());
        // the PENDING one survives untouched
        assertTrue(submissionRepository.findById(pending.getId()).isPresent());
    }

    @Test
    void submissionEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(post("/process-submissions")
                        .content("{}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/process-submissions/mine"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/process-submissions"))
                .andExpect(status().isUnauthorized());
    }
}