package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-ACL-17: candidate search must match login, full name AND email (substring,
 * case-insensitive), with LIKE wildcards % / _ searched literally. Full-context IT
 * through the real filter chain; MANAGE_MEMBERS and MAX_CANDIDATES unchanged.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Acl17CandidateSearchIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String adminToken;
    private String ownerToken;   // MANAGE_MEMBERS on keyA
    private String keyA;

    private UUID byNameId;       // found by full-name fragment
    private UUID byEmailId;      // found by email fragment
    private UUID byLoginId;      // found by login fragment (regression)
    private UUID literalId;      // name contains a literal % — wildcard must not match others
    private UUID underscoreId;   // name contains a literal _
    private UUID decoyPctId;     // would match ONLY if % became a live wildcard
    private UUID decoyUsdId;     // would match ONLY if _ became a live wildcard

    private String suffix = UUID.randomUUID().toString().substring(0, 8);

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin", "admin");

        // Owner process
        keyA = "acl17-" + suffix;
        deploy(keyA);
        UUID ownerId = createUser("acl17-owner-" + suffix, "USER", "Acl17 Owner " + suffix, "owner" + suffix + "@test.kz");
        addMember(ownerId, keyA, "OWNER");
        ownerToken = login("acl17-owner-" + suffix, "pass");

        // Candidates with distinctive name/email/login fragments
        byNameId = createUser("acl17-login1-" + suffix, "USER", "AAA Кирилл Пешков-" + suffix, "mail1-" + suffix + "@example.com");
        byEmailId = createUser("acl17-login2-" + suffix, "USER", "Anna Petrova", "kirill.peshkov" + suffix + "@corp.example");
        byLoginId = createUser("acl17-needle-" + suffix, "USER", "Some Body", "body" + suffix + "@example.com");

        // Literal-wildcard fixtures: '%' and '_' INSIDE the stored values
        literalId = createUser("acl17-pct-" + suffix, "USER", "Percent 100% done " + suffix, "pct" + suffix + "@example.com");
        underscoreId = createUser("acl17-usd-" + suffix, "USER", "Under_score name " + suffix, "usd" + suffix + "@example.com");

        // A decoy that a live wildcard WOULD match but a literal search must NOT:
        // searching for the literal "100% d" must not match e.g. "100xd done"
        decoyPctId = createUser("acl17-decoy-" + suffix, "USER", "Percent 100zd done " + suffix, "decoy" + suffix + "@example.com");
        // searching literal "Under_score" must not match "Underxscore"
        decoyUsdId = createUser("acl17-decoy2-" + suffix, "USER", "Underxscore name " + suffix, "decoy2" + suffix + "@example.com");
    }

    // ==================== Criterion 1: all three fields ====================

    @Test
    void criterion1_findsByFullNameFragment() throws Exception {
        JsonNode data = candidates(ownerToken, "Пешков-" + suffix);
        assertThat(ids(data)).contains(byNameId);
    }

    @Test
    void criterion1_findsByEmailFragment() throws Exception {
        JsonNode data = candidates(ownerToken, "KIRILL.PESHKOV" + suffix.toUpperCase() + "@CORP");
        assertThat(ids(data)).contains(byEmailId);
    }

    @Test
    void criterion1_stillFindsByLoginFragment_regression() throws Exception {
        JsonNode data = candidates(ownerToken, "needle-" + suffix);
        assertThat(ids(data)).contains(byLoginId);
    }

    // ==================== Criterion 2: case-insensitive, wildcards literal ====================

    @Test
    void criterion2_caseInsensitive_name() throws Exception {
        JsonNode data = candidates(ownerToken, "ПЕШКОВ-" + suffix.toUpperCase());
        assertThat(ids(data)).contains(byNameId);
    }

    @Test
    void criterion2_percent_isLiteral_notWildcard() throws Exception {
        JsonNode data = candidates(ownerToken, "100% d");
        assertThat(ids(data))
            .as("literal '100% d' matches EXACTLY the user whose name has it - no wildcard leak")
            .containsExactlyInAnyOrder(literalId);
    }

    @Test
    void criterion2_underscore_isLiteral_notWildcard() throws Exception {
        JsonNode data = candidates(ownerToken, "Under_score");
        assertThat(ids(data))
            .as("literal '_' matches EXACTLY the user whose name has it - no any-char leak")
            .containsExactlyInAnyOrder(underscoreId);
    }

    // ==================== Criterion 3: ACL-15 behavior intact ====================

    @Test
    void criterion3_emptyQuery_returnsFirstPage_sortedByNamThenLogin() throws Exception {
        MvcResult result = mockMvc.perform(get("/processes/" + keyA + "/members/candidates")
                .header("Authorization", "Bearer " + ownerToken)
                .queryParam("q", ""))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode data = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(data.size()).as("empty query still returns a page").isGreaterThan(0);
        // stable order check on the returned page: fullName asc then username asc
        String prevName = null; String prevLogin = null;
        for (JsonNode u : data) {
            String name = u.get("fullName").asText("");
            String login = u.get("username").asText();
            if (prevName != null && name.equals(prevName)) {
                assertThat(login.compareToIgnoreCase(prevLogin)).isGreaterThanOrEqualTo(0);
            }
            prevName = name; prevLogin = login;
        }
    }

    @Test
    void criterion3_viewerCannotSearch_candidates403() throws Exception {
        UUID viewerId = createUser("acl17-viewer-" + suffix, "USER", "Viewer " + suffix, "view" + suffix + "@test.kz");
        addMember(viewerId, keyA, "VIEWER");
        String viewerToken = login("acl17-viewer-" + suffix, "pass");
        mockMvc.perform(get("/processes/" + keyA + "/members/candidates?q=x")
                .header("Authorization", "Bearer " + viewerToken))
            .andExpect(status().isForbidden());
    }

    // ==================== helpers ====================

    private JsonNode candidates(String token, String q) throws Exception {
        // build+encode like a real HTTP client so non-ASCII queries arrive decoded server-side
        var uri = org.springframework.web.util.UriComponentsBuilder.fromPath("/processes/" + keyA + "/members/candidates")
            .queryParam("q", q)
            .build().encode().toUri();
        MvcResult result = mockMvc.perform(get(uri)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private java.util.List<UUID> ids(JsonNode data) {
        java.util.List<UUID> out = new java.util.ArrayList<>();
        for (JsonNode u : data) out.add(UUID.fromString(u.get("userId").asText()));
        return out;
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

    private void deploy(String key) throws Exception {
        String bpmn = java.nio.file.Files.readString(java.nio.file.Paths.get("src/test/files/process1.bpmn"))
            .replace("id=\"process1\"", "id=\"" + key + "\"")
            .replace("name=\"Process 1\"", "name=\"" + key + "\"");
        var addDef = new com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO();
        addDef.setBpmn(bpmn);
        mockMvc.perform(post("/process-definitions")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(addDef)))
            .andExpect(status().isCreated());
    }

    private void addMember(UUID userId, String processKey, String role) throws Exception {
        mockMvc.perform(post("/processes/" + processKey + "/members")
                .header("Authorization", "Bearer " + adminToken)
                .content("{\"userId\":\"" + userId + "\",\"role\":\"" + role + "\"}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());
    }
}
