package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.ProcessSubmissionDTO;
import com.zorrodev.bpm.contract.dto.SubmitProcessSubmissionDTO;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-ACL-9: submission identity enrichment, camelCase keys, members visible to all.
 *
 * POF (G-K):
 * 1. Remove submitter enrichment → criterion 1 fails (submittedByUsername null)
 * 2. Revert KEY_PATTERN → criterion 5 fails (approvalProcess rejected)
 * 3. Make KEY_PATTERN permissive (.*) → criterion 6 fails (invalid keys accepted)
 * 4. Restore requireOperate(VIEW_MEMBERS) → criterion 8 fails (non-member gets 403)
 * 5. Remove guard from addMember → criterion 9 fails (non-member can add)
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Acl9SubmissionIdentityIntegrationTest {

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
    private String user2Token;
    private UUID user2Id;

    @BeforeAll
    void setUp() throws Exception {
        // Create admin
        UiUserEntity admin = createOrUpdateUser("admin", "Admin User", "admin@test.com", "ADMIN");
        adminId = admin.getId();
        adminToken = login("admin");

        // Create regular users
        UiUserEntity user = createOrUpdateUser("user1", "User One", "user1@test.com", "USER");
        userId = user.getId();
        userToken = login("user1");

        UiUserEntity user2 = createOrUpdateUser("user2", "User Two", "user2@test.com", "USER");
        user2Id = user2.getId();
        user2Token = login("user2");
    }

    @AfterEach
    void tearDown() {
        createdSubmissionIds.forEach(id -> submissionRepository.deleteById(id));
        createdSubmissionIds.clear();
        createdKeys.forEach(key -> {
            processRepository.findByDefinitionKey(key).ifPresent(p -> {
                processMemberRepository.deleteAll(processMemberRepository.findByProcessId(p.getId()));
                processRepository.delete(p);
            });
        });
        createdKeys.clear();
    }

    // === CRITERIA 1-3: DTO enrichment ===

    @Test
    void criterion1_submittedByUsernameFullNameEmailPopulated() throws Exception {
        String key = uniqueKey("enrich");
        ProcessSubmissionDTO dto = submit(userToken, bpmnFor(key));

        assertNotNull(dto.getSubmittedByUsername());
        assertEquals("user1", dto.getSubmittedByUsername());
        assertEquals("User One", dto.getSubmittedByFullName());
        assertEquals("user1@test.com", dto.getSubmittedByEmail());
    }

    @Test
    void criterion2_reviewedByUsernamePopulatedAfterApproval() throws Exception {
        String key = uniqueKey("reviewed");
        ProcessSubmissionDTO submitted = submit(userToken, bpmnFor(key));
        ProcessSubmissionDTO reviewed = approveAsAdmin(submitted.getId(), 200);

        assertNotNull(reviewed.getReviewedByUsername());
        assertEquals("admin", reviewed.getReviewedByUsername());
    }

    @Test
    void criterion2_reviewedByUsernameNullWhenPending() throws Exception {
        String key = uniqueKey("pending");
        ProcessSubmissionDTO dto = submit(userToken, bpmnFor(key));

        assertNull(dto.getReviewedByUsername());
    }

    @Test
    void criterion3_submissionQueueOnlyForSuperAdmin() throws Exception {
        // Admin can see
        mockMvc.perform(get("/api/submissions")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // Regular user gets 403
        mockMvc.perform(get("/api/submissions")
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    // === CRITERIA 5-7: camelCase key ===

    @Test
    void criterion5_approvalProcessKeyAccepted() throws Exception {
        String key = "approvalProcess";
        ProcessSubmissionDTO dto = submit(userToken, bpmnFor(key));
        assertNotNull(dto.getId());
        assertEquals(key, dto.getProcessKey());
    }

    @Test
    void criterion6_invalidKeysRejected() throws Exception {
        // Dot in key
        submitExpect(userToken, bpmnFor("key.bpmn"), 400);
        // Space in key
        submitExpect(userToken, bpmnFor("key name"), 400);
        // Leading digit
        submitExpect(userToken, bpmnFor("1key"), 400);
        // Too short (< 3)
        submitExpect(userToken, bpmnFor("ab"), 400);
    }

    @Test
    void criterion7_caseSensitiveCoexistence() throws Exception {
        String upper = uniqueKey("Approval");
        String lower = upper.toLowerCase();

        submit(userToken, bpmnFor(upper));
        createdKeys.add(upper);

        submit(userToken, bpmnFor(lower));
        createdKeys.add(lower);

        // Both exist as separate processes
        assertNotNull(processRepository.findByDefinitionKey(upper).orElse(null));
        assertNotNull(processRepository.findByDefinitionKey(lower).orElse(null));
    }

    // === CRITERIA 8-10: members visible to all ===

    @Test
    void criterion8_nonMemberSeesMemberList() throws Exception {
        // Create a process owned by user1
        String key = uniqueKey("visible");
        ProcessSubmissionDTO submitted = submit(userToken, bpmnFor(key));
        approveAsAdmin(submitted.getId(), 200);

        // user2 (non-member) can see the member list
        mockMvc.perform(get("/api/processes/" + key + "/members")
                .header("Authorization", "Bearer " + user2Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1)); // owner
    }

    @Test
    void criterion9_nonMemberStill403OnManage() throws Exception {
        String key = uniqueKey("manage");
        ProcessSubmissionDTO submitted = submit(userToken, bpmnFor(key));
        approveAsAdmin(submitted.getId(), 200);

        // user2 (non-member) gets 403 on addMember
        String body = "{\"userId\":\"" + user2Id + "\",\"role\":\"VIEWER\"}";
        mockMvc.perform(post("/api/processes/" + key + "/members")
                .header("Authorization", "Bearer " + user2Token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void criterion10_unauthenticatedGets401() throws Exception {
        String key = uniqueKey("unauth");
        ProcessSubmissionDTO submitted = submit(userToken, bpmnFor(key));
        approveAsAdmin(submitted.getId(), 200);

        mockMvc.perform(get("/api/processes/" + key + "/members"))
                .andExpect(status().isUnauthorized());
    }

    // === Helpers ===

    private UiUserEntity createOrUpdateUser(String username, String fullName, String email, String role) {
        UiUserEntity existing = userRepository.findByUsername(username).orElse(null);
        if (existing != null) return existing;

        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash("password123"));
        user.setFullName(fullName);
        user.setEmail(email);
        user.setRole(role);
        user.setActive(true);
        user.setForcePasswordChange(false);
        return userRepository.save(user);
    }

    private String login(String username) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"password123\"}";
        String result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(result).get("token").asText();
    }

    private ProcessSubmissionDTO submit(String token, String bpmnXml) throws Exception {
        SubmitProcessSubmissionDTO dto = new SubmitProcessSubmissionDTO();
        dto.setBpmn(bpmnXml);
        String result = mockMvc.perform(post("/api/submissions")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        ProcessSubmissionDTO submission = mapper.readValue(result, ProcessSubmissionDTO.class);
        createdSubmissionIds.add(submission.getId());
        return submission;
    }

    private void submitExpect(String token, String bpmnXml, int expectedStatus) throws Exception {
        SubmitProcessSubmissionDTO dto = new SubmitProcessSubmissionDTO();
        dto.setBpmn(bpmnXml);
        mockMvc.perform(post("/api/submissions")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(dto)))
                .andExpect(status().is(expectedStatus));
    }

    private ProcessSubmissionDTO approveAsAdmin(UUID submissionId, int expectedStatus) throws Exception {
        String result = mockMvc.perform(post("/api/submissions/" + submissionId + "/approve")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        if (expectedStatus != 200) return null;
        ProcessSubmissionDTO dto = mapper.readValue(result, ProcessSubmissionDTO.class);
        createdKeys.add(dto.getProcessKey());
        return dto;
    }

    private String bpmnFor(String key) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:camunda=\"http://camunda.org/schema/1.0/bpmn\""
            + " id=\"definitions\" targetNamespace=\"http://bpmn.io/schema/bpmn\">"
            + "  <process id=\"" + key + "\" name=\"Test Process\" isExecutable=\"true\">"
            + "    <startEvent id=\"start\" />"
            + "  </process>"
            + "</definitions>";
    }

    private String uniqueKey(String prefix) {
        String key = prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
        createdKeys.add(key);
        return key;
    }
}
