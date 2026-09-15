package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.LoginDTO;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
    @Autowired private JdbcTemplate jdbcTemplate;

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
        adminId = createOrUpdateUser("acl9-admin", "Admin User", "admin@test.com", "SUPER_ADMIN");
        adminToken = login("acl9-admin");

        // Create regular users
        userId = createOrUpdateUser("acl9-user1", "User One", "user1@test.com", "USER");
        userToken = login("acl9-user1");

        user2Id = createOrUpdateUser("acl9-user2", "User Two", "user2@test.com", "USER");
        user2Token = login("acl9-user2");
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
        assertEquals("acl9-user1", dto.getSubmittedByUsername());
        assertEquals("User One", dto.getSubmittedByFullName());
        assertEquals("user1@test.com", dto.getSubmittedByEmail());
    }

    @Test
    void criterion2_reviewedByUsernamePopulatedAfterApproval() throws Exception {
        String key = uniqueKey("reviewed");
        ProcessSubmissionDTO submitted = submit(userToken, bpmnFor(key));
        ProcessSubmissionDTO reviewed = approveAsAdmin(submitted.getId(), 200);

        assertNotNull(reviewed.getReviewedByUsername());
        assertEquals("acl9-admin", reviewed.getReviewedByUsername());
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
        mockMvc.perform(get("/process-submissions")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // Regular user gets 403
        mockMvc.perform(get("/process-submissions")
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    // === CRITERIA 5-7: camelCase key ===

    @Test
    void criterion4_deletedUserFieldsNull() throws Exception {
        // Create a temporary user, submit as them, then delete them
        UUID tempUserId = createOrUpdateUser("acl9-temp", "Temp User", "temp@test.com", "USER");
        String tempToken = login("acl9-temp");

        String key = uniqueKey("deleted");
        ProcessSubmissionDTO dto = submit(tempToken, bpmnFor(key));

        // Delete user with FK checks disabled (H2 enforces FK, can't delete normally)
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        userRepository.deleteById(tempUserId);
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");

        // Fetch submission again — enrichment should return nulls, not crash
        String result = mockMvc.perform(get("/process-submissions")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<ProcessSubmissionDTO> all = List.of(mapper.readValue(result, ProcessSubmissionDTO[].class));
        ProcessSubmissionDTO fetched = all.stream()
                .filter(s -> s.getId().equals(dto.getId()))
                .findFirst().orElseThrow();

        assertNull(fetched.getSubmittedByUsername());
        assertNull(fetched.getSubmittedByFullName());
        assertNull(fetched.getSubmittedByEmail());
    }

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
        // Too long (> 64)
        String longKey = "a".repeat(65);
        submitExpect(userToken, bpmnFor(longKey), 400);
    }

    @Test
    void criterion7_caseSensitiveCoexistence() throws Exception {
        String upper = uniqueKey("Approval");
        String lower = upper.toLowerCase();

        ProcessSubmissionDTO first = submit(userToken, bpmnFor(upper));
        ProcessSubmissionDTO second = submit(userToken, bpmnFor(lower));

        // Both submissions accepted as separate keys
        assertNotNull(first.getId());
        assertNotNull(second.getId());
        assertEquals(upper, first.getProcessKey());
        assertEquals(lower, second.getProcessKey());

        // Approve both — two distinct processes must live side by side
        approveAsAdmin(first.getId(), 200);
        approveAsAdmin(second.getId(), 200);

        assertTrue(processRepository.findByDefinitionKey(upper).isPresent());
        assertTrue(processRepository.findByDefinitionKey(lower).isPresent());
        assertNotEquals(
            processRepository.findByDefinitionKey(upper).orElseThrow().getId(),
            processRepository.findByDefinitionKey(lower).orElseThrow().getId());
    }

    // === CRITERIA 8-10: members visible to all ===

    @Test
    void criterion8_nonMemberSeesMemberList() throws Exception {
        // Create a process owned by user1
        String key = uniqueKey("visible");
        ProcessSubmissionDTO submitted = submit(userToken, bpmnFor(key));
        approveAsAdmin(submitted.getId(), 200);

        // user2 (non-member) can see the member list
        mockMvc.perform(get("/processes/" + key + "/members")
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
        mockMvc.perform(post("/processes/" + key + "/members")
                .header("Authorization", "Bearer " + user2Token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isForbidden());

        // user2 (non-member) gets 403 on changeRole
        mockMvc.perform(patch("/processes/" + key + "/members/" + user2Id)
                .header("Authorization", "Bearer " + user2Token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isForbidden());

        // user2 (non-member) gets 403 on removeMember
        mockMvc.perform(delete("/processes/" + key + "/members/" + user2Id)
                .header("Authorization", "Bearer " + user2Token))
                .andExpect(status().isForbidden());
    }

    @Test
    void criterion10_unauthenticatedGets401() throws Exception {
        String key = uniqueKey("unauth");
        ProcessSubmissionDTO submitted = submit(userToken, bpmnFor(key));
        approveAsAdmin(submitted.getId(), 200);

        mockMvc.perform(get("/processes/" + key + "/members"))
                .andExpect(status().isUnauthorized());
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
        SubmitProcessSubmissionDTO dto = new SubmitProcessSubmissionDTO();
        dto.setBpmn(bpmnXml);
        String result = mockMvc.perform(post("/process-submissions")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        ProcessSubmissionDTO submission = mapper.readValue(result, ProcessSubmissionDTO.class);
        createdSubmissionIds.add(submission.getId());
        return submission;
    }

    private void submitExpect(String token, String bpmnXml, int expectedStatus) throws Exception {
        SubmitProcessSubmissionDTO dto = new SubmitProcessSubmissionDTO();
        dto.setBpmn(bpmnXml);
        mockMvc.perform(post("/process-submissions")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(dto)))
                .andExpect(status().is(expectedStatus));
    }

    private ProcessSubmissionDTO approveAsAdmin(UUID submissionId, int expectedStatus) throws Exception {
        String result = mockMvc.perform(post("/process-submissions/" + submissionId + "/approve")
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
