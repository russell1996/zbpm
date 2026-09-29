package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.dto.ProcessSubmissionDTO;
import com.zorrodev.bpm.contract.dto.SubmitProcessSubmissionDTO;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
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

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-ACL-7 — three access-surface endpoints, full-context tests (V11) through the real
 * filter chain:
 *   1. GET /me/memberships            — self only
 *   2. GET /process-submissions/{id}/bpmn — SUPER_ADMIN only
 *   3. GET /processes/{key}/members/candidates?q= — MANAGE_MEMBERS, non-empty q, minimal DTO
 *
 * POF (G-K / P-46): criteria 1, 5 and 6 each have their own RED by mutating PROD code —
 * see the report; the tests below assert the full G-K shape (authorized GETS the data,
 * unauthorized does NOT, principals are DIFFERENT).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Acl7SelfAndCandidateEndpointsIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private ProcessMemberRepository processMemberRepository;
    @Autowired private PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String adminToken;
    private UUID adminId;
    private UUID userAId;
    private String userAToken;
    private UUID userBId;
    private String userBToken;
    private UUID outsiderId;

    /** Process owned by A (B added as VIEWER for the 403 case). */
    private String keyA;
    /** Process owned by B — must NEVER appear in A's /me/memberships. */
    private String keyB;
    /** Process owned by the outsider — must NEVER appear in A's or B's /me/memberships. */
    private String keyC;

    private String submissionBpmnKey;
    private UUID submissionId;

    @BeforeAll
    void setup() throws Exception {
        adminId = createUser("acl7-admin", "SUPER_ADMIN");
        userAId = createUser("acl7-user-a", "USER");
        userBId = createUser("acl7-user-b", "USER");
        outsiderId = createUser("acl7-outsider", "USER");

        adminToken = login("acl7-admin", "pass");
        userAToken = login("acl7-user-a", "pass");
        userBToken = login("acl7-user-b", "pass");

        keyA = uniqueKey("acl7a");
        deploy(keyA);
        addMember(keyA, userAId, "OWNER");
        addMember(keyA, userBId, "VIEWER");

        keyB = uniqueKey("acl7b");
        deploy(keyB);
        addMember(keyB, userBId, "OWNER");

        keyC = uniqueKey("acl7c");
        deploy(keyC);
        addMember(keyC, outsiderId, "OWNER");

        // A submission for the bpmn endpoint
        submissionBpmnKey = uniqueKey("acl7s");
        submissionId = submit(userAToken, bpmnFor(submissionBpmnKey)).getId();
    }

    // ─────────────────────────────────────────────────────────────
    // Criterion 1: GET /me/memberships returns ONLY own memberships
    // ─────────────────────────────────────────────────────────────

    @Test
    void meMemberships_returnsOnlyOwn() throws Exception {
        MvcResult a = mockMvc.perform(get("/me/memberships")
                .header("Authorization", "Bearer " + userAToken))
            .andExpect(status().isOk())
            .andReturn();
        String bodyA = a.getResponse().getContentAsString();
        assertTrue(bodyA.contains(keyA), "A must see its OWN process " + keyA + ": " + bodyA);
        assertFalse(bodyA.contains(keyB), "A must NOT see B's process " + keyB + ": " + bodyA);
        assertFalse(bodyA.contains(keyC), "A must NOT see the outsider's process " + keyC + ": " + bodyA);

        MvcResult b = mockMvc.perform(get("/me/memberships")
                .header("Authorization", "Bearer " + userBToken))
            .andExpect(status().isOk())
            .andReturn();
        String bodyB = b.getResponse().getContentAsString();
        assertTrue(bodyB.contains(keyB), "B must see its OWN process " + keyB + ": " + bodyB);
        assertTrue(bodyB.contains(keyA), "B is a VIEWER of " + keyA + " — a membership it legitimately has: " + bodyB);
        assertFalse(bodyB.contains(keyC), "B must NOT see the outsider's process " + keyC + ": " + bodyB);

        MvcResult c = mockMvc.perform(get("/me/memberships")
                .header("Authorization", "Bearer " + login("acl7-outsider", "pass")))
            .andExpect(status().isOk())
            .andReturn();
        String bodyC = c.getResponse().getContentAsString();
        assertTrue(bodyC.contains(keyC), "C must see its OWN process " + keyC + ": " + bodyC);
        assertFalse(bodyC.contains(keyA), "C must NOT see A's process " + keyA + ": " + bodyC);
        assertFalse(bodyC.contains(keyB), "C must NOT see B's process " + keyB + ": " + bodyC);
    }

    @Test
    void meMemberships_showsRoleAndProcessKey() throws Exception {
        MvcResult a = mockMvc.perform(get("/me/memberships")
                .header("Authorization", "Bearer " + userAToken))
            .andExpect(status().isOk())
            .andReturn();
        String body = a.getResponse().getContentAsString();
        assertTrue(body.contains("OWNER"), "role must be present: " + body);
        assertTrue(body.contains(userAId.toString()), "userId must be present: " + body);
    }

    // ─────────────────────────────────────────────────────────────
    // Criteria 9/10 (WO-ACL-7 пункт 4): MemberDTO carries fullName+email, nothing more
    // ─────────────────────────────────────────────────────────────

    @Test
    void meMemberships_carriesFullNameAndEmail() throws Exception {
        MvcResult a = mockMvc.perform(get("/me/memberships")
                .header("Authorization", "Bearer " + userAToken))
            .andExpect(status().isOk())
            .andReturn();
        String body = a.getResponse().getContentAsString();
        assertTrue(body.contains("ACL7 acl7-user-a"), "fullName must be present: " + body);
        assertTrue(body.contains("acl7-user-a@example.com"), "email must be present: " + body);
    }

    @Test
    void meMemberships_dtoHasNoExtraFields() throws Exception {
        MvcResult a = mockMvc.perform(get("/me/memberships")
                .header("Authorization", "Bearer " + userAToken))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode arr = mapper.readTree(a.getResponse().getContentAsString());
        assertTrue(arr.isArray() && arr.size() >= 1, "expected own memberships: " + arr);
        Set<String> fields = new HashSet<>();
        arr.get(0).fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("userId", "username", "fullName", "email", "role", "addedBy", "addedAt", "processKey", "isSystem"),
            fields, "MemberDTO must expose exactly the agreed fields: " + arr);
    }

    // ─────────────────────────────────────────────────────────────
    // Criterion 2: unauthenticated → 401; someone else's memberships unreachable
    // ─────────────────────────────────────────────────────────────

    @Test
    void meMemberships_withoutToken_is401() throws Exception {
        mockMvc.perform(get("/me/memberships"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void othersMemberships_cannotBeRead_byNonAdmin() throws Exception {
        // The admin-only directory endpoint stays closed to non-SUPER_ADMINs:
        mockMvc.perform(get("/admin/users/" + userBId + "/memberships")
                .header("Authorization", "Bearer " + userAToken))
            .andExpect(status().isForbidden());
    }

    // ─────────────────────────────────────────────────────────────
    // Criteria 3/4: GET /process-submissions/{id}/bpmn
    // ─────────────────────────────────────────────────────────────

    @Test
    void submissionBpmn_adminGetsTheModel() throws Exception {
        MvcResult result = mockMvc.perform(get("/process-submissions/" + submissionId + "/bpmn")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andReturn();
        String xml = result.getResponse().getContentAsString();
        assertTrue(xml.contains(submissionBpmnKey), "BPMN must contain the submitted process key: " + xml.substring(0, Math.min(200, xml.length())));
        assertTrue(xml.contains("<bpmn"), "BPMN must be the raw XML: " + xml.substring(0, Math.min(200, xml.length())));
    }

    @Test
    void submissionBpmn_regularUser_is403() throws Exception {
        mockMvc.perform(get("/process-submissions/" + submissionId + "/bpmn")
                .header("Authorization", "Bearer " + userBToken))
            .andExpect(status().isForbidden());
    }

    @Test
    void submissionBpmn_unknownId_is404() throws Exception {
        mockMvc.perform(get("/process-submissions/" + UUID.randomUUID() + "/bpmn")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isNotFound());
    }

    // ─────────────────────────────────────────────────────────────
    // Criterion 5: candidates requires MANAGE_MEMBERS on the process
    // ─────────────────────────────────────────────────────────────

    @Test
    void candidates_ownerGets200() throws Exception {
        mockMvc.perform(get("/processes/" + keyA + "/members/candidates")
                .header("Authorization", "Bearer " + userAToken)
                .param("q", "acl7"))
            .andExpect(status().isOk());
    }

    @Test
    void candidates_viewer_is403() throws Exception {
        // B is a VIEWER of keyA — sees members (VIEW_MEMBERS) but must NOT manage them
        mockMvc.perform(get("/processes/" + keyA + "/members/candidates")
                .header("Authorization", "Bearer " + userBToken)
                .param("q", "acl7"))
            .andExpect(status().isForbidden());
    }

    @Test
    void candidates_outsider_is403() throws Exception {
        mockMvc.perform(get("/processes/" + keyA + "/members/candidates")
                .header("Authorization", "Bearer " + login("acl7-outsider", "pass"))
                .param("q", "acl7"))
            .andExpect(status().isForbidden());
    }

    // ─────────────────────────────────────────────────────────────
    // Criterion 6 (WO-ACL-15 part B): empty/short q → the FIRST PAGE, not 400
    // ─────────────────────────────────────────────────────────────

    @Test
    void candidates_emptyQuery_returnsFirstPage() throws Exception {
        // WO-ACL-15 criterion 6: an empty q is allowed and returns a page (≤ cap),
        // not a 400 — the dialog can show the list without typing first.
        MvcResult result = mockMvc.perform(get("/processes/" + keyA + "/members/candidates")
                .header("Authorization", "Bearer " + userAToken)
                .param("q", ""))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode arr = mapper.readTree(result.getResponse().getContentAsString());
        assertTrue(arr.isArray(), "expected an array: " + arr);
        assertTrue(arr.size() <= 20, "the page must be capped at 20: " + arr.size());
        // the page contains candidates (the seeded users), not an error
        assertTrue(arr.size() >= 1, "empty q must return at least the seeded users: " + arr);
    }

    @Test
    void candidates_shortQuery_is200() throws Exception {
        // WO-ACL-15 part B: the 3-char threshold is gone — a short fragment is just
        // a wide search, still capped and still MANAGE_MEMBERS-gated.
        mockMvc.perform(get("/processes/" + keyA + "/members/candidates")
                .header("Authorization", "Bearer " + userAToken)
                .param("q", "ab"))
            .andExpect(status().isOk());
    }

    @Test
    void candidates_emptyQuery_cappedAtTwenty() throws Exception {
        // WO-ACL-15 criterion 7: the cap is what keeps the endpoint from being a
        // user directory — with 25 non-member users, an empty q returns exactly 20.
        for (int i = 0; i < 25; i++) {
            createUser("acl7-many-" + i, "USER");
        }
        MvcResult result = mockMvc.perform(get("/processes/" + keyA + "/members/candidates")
                .header("Authorization", "Bearer " + userAToken)
                .param("q", ""))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode arr = mapper.readTree(result.getResponse().getContentAsString());
        assertEquals(20, arr.size(), "empty q must return exactly the first page (20), not the whole table: " + arr.size());
    }

    @Test
    void candidates_emptyQuery_sortedByNameThenLogin() throws Exception {
        // WO-ACL-15 part B: the page must be stably sorted by name, then login —
        // a list whose order changes between openings is worse than an empty one.
        // A shared unique prefix isolates the pair from the rest of the page.
        String r = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        String ann = "acl7-srt-" + r + "-ann";
        String zoe = "acl7-srt-" + r + "-zoe";
        createUser(ann, "USER", "Ann A.", "ann@example.com");
        createUser(zoe, "USER", "Zoe Z.", "zoe@example.com");
        MvcResult result = mockMvc.perform(get("/processes/" + keyA + "/members/candidates")
                .header("Authorization", "Bearer " + userAToken)
                .param("q", "acl7-srt-" + r))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode arr = mapper.readTree(result.getResponse().getContentAsString());
        assertEquals(2, arr.size(), "the isolated pair must be the whole page: " + arr);
        assertEquals(ann, arr.get(0).get("username").asText(),
            "Ann (name 'Ann A.') must sort before Zoe (name 'Zoe Z.'): " + arr);
        assertEquals(zoe, arr.get(1).get("username").asText());
    }

    // ─────────────────────────────────────────────────────────────
    // Criteria 7/8: no existing members; only userId + username
    // ─────────────────────────────────────────────────────────────

    @Test
    void candidates_doesNotReturnExistingMembers() throws Exception {
        // q "acl7-user" matches A (OWNER of keyA), B (VIEWER of keyA) and the outsider.
        // A and B are members → excluded; the outsider is the only candidate.
        MvcResult result = mockMvc.perform(get("/processes/" + keyA + "/members/candidates")
                .header("Authorization", "Bearer " + userAToken)
                .param("q", "acl7-user"))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode arr = mapper.readTree(result.getResponse().getContentAsString());
        assertTrue(arr.isArray(), "expected an array: " + arr);
        for (JsonNode node : arr) {
            String userId = node.get("userId").asText();
            String username = node.get("username").asText();
            assertFalse(userId.equals(userAId.toString()), "OWNER must not be a candidate");
            assertFalse(userId.equals(userBId.toString()), "existing VIEWER member must not be a candidate");
            assertTrue(username.equals("acl7-outsider"), "only the outsider may match: " + arr);
        }
    }

    @Test
    void candidates_carriesFullNameAndEmail() throws Exception {
        MvcResult result = mockMvc.perform(get("/processes/" + keyA + "/members/candidates")
                .header("Authorization", "Bearer " + userAToken)
                .param("q", "acl7-outsider"))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode arr = mapper.readTree(result.getResponse().getContentAsString());
        assertEquals(1, arr.size(), "exactly one match expected: " + arr);
        Set<String> fields = new HashSet<>();
        arr.get(0).fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("userId", "username", "fullName", "email"), fields,
            "the candidate DTO must expose userId, username, fullName and email: " + arr);
        assertEquals("acl7-outsider", arr.get(0).get("username").asText());
        assertEquals("ACL7 acl7-outsider", arr.get(0).get("fullName").asText(),
            "fullName must be populated from the account (WO-ACL-15 criterion 1): " + arr);
        assertEquals("acl7-outsider@example.com", arr.get(0).get("email").asText(),
            "email must be populated from the account (WO-ACL-15 criterion 1): " + arr);
    }

    @Test
    void candidates_accountWithoutIdentity_hasEmptyStrings() throws Exception {
        // WO-ACL-15 criterion 1: fullName/email are EMPTY STRINGS (never null) when the
        // account has none — the dialog renders absence, not the literal "null".
        String noIdUsername = "acl7-noid-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        createUser(noIdUsername, "USER", null, null);
        MvcResult result = mockMvc.perform(get("/processes/" + keyA + "/members/candidates")
                .header("Authorization", "Bearer " + userAToken)
                .param("q", noIdUsername))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode arr = mapper.readTree(result.getResponse().getContentAsString());
        assertEquals(1, arr.size(), "exactly one match expected: " + arr);
        JsonNode node = arr.get(0);
        assertEquals("", node.get("fullName").asText(), "fullName must be '' when absent, not null: " + node);
        assertEquals("", node.get("email").asText(), "email must be '' when absent, not null: " + node);
        assertFalse(node.get("fullName").isNull(), "fullName must never serialize as JSON null: " + node);
        assertFalse(node.get("email").isNull(), "email must never serialize as JSON null: " + node);
    }

    // ─────────────────────────────────────────────────────────────
    // Criterion 3 (WO-ACL-15): /users stays SUPER_ADMIN-only — the
    // candidates endpoint must not become a user directory through a side door
    // ─────────────────────────────────────────────────────────────

    @Test
    void users_directory_nonAdmin_is403() throws Exception {
        // A MANAGE_MEMBERS holder (OWNER of keyA) still cannot read the user directory
        mockMvc.perform(get("/users")
                .header("Authorization", "Bearer " + userAToken))
            .andExpect(status().isForbidden());
    }

    @Test
    void users_directory_superAdmin_isOk() throws Exception {
        mockMvc.perform(get("/users")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk());
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private UUID createUser(String username, String globalRole) {
        return createUser(username, globalRole, "ACL7 " + username, username + "@example.com");
    }

    private UUID createUser(String username, String globalRole, String fullName, String email) {
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash("pass"));
        user.setFullName(fullName);
        user.setEmail(email);
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
        MvcResult result = mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer")
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class).getToken();
    }

    private String uniqueKey(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private String bpmnFor(String key) throws Exception {
        String bpmn = new String(Files.readAllBytes(Paths.get("src/test/files/process1.bpmn")));
        return bpmn.replace("id=\"process1\"", "id=\"" + key + "\"")
            .replace("name=\"Process 1\"", "name=\"" + key + "\"");
    }

    private void deploy(String key) throws Exception {
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn(bpmnFor(key));
        mockMvc.perform(post("/process-definitions")
                .header("Authorization", "Bearer " + adminToken)
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated());
    }

    private void addMember(String key, UUID userId, String role) throws Exception {
        mockMvc.perform(post("/processes/" + key + "/members")
                .header("Authorization", "Bearer " + adminToken)
                .content("{\"userId\":\"" + userId + "\",\"role\":\"" + role + "\"}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());
    }

    private ProcessSubmissionDTO submit(String token, String bpmnXml) throws Exception {
        SubmitProcessSubmissionDTO dto = new SubmitProcessSubmissionDTO();
        dto.setBpmn(bpmnXml);
        MvcResult result = mockMvc.perform(post("/process-submissions")
                .header("Authorization", "Bearer " + token)
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), ProcessSubmissionDTO.class);
    }
}
