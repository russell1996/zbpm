package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-SEC-74 (N06): an idempotency cache hit must be re-authorized against the
 * CURRENT policy before the stored bytes are served — and the
 * {@code X-On-Behalf-Of} attribution claim is part of the semantic fingerprint.
 *
 * <ul>
 *   <li>Criterion 1: member completes a task with key K (200, response stored);
 *       admin revokes the membership; replay with the same key+body → 403, NOT
 *       the stale stored success.</li>
 *   <li>Criterion 2: same key, same body, but a DIFFERENT {@code X-On-Behalf-Of}
 *       claim → 422 (different semantic request), not the other attribution's
 *       replay; re-sending the original claim → 200 replay with identical bytes.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("test")
class Sec74IdempotencyReplayAuthzIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String adminToken;

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin", "admin");
    }

    // --- Criterion 1: revoked membership → replay denied ---

    @Test
    void replay_afterMembershipRevoked_returns403_notStaleSuccess() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 4);
        String memberName = "sec74member" + suffix;
        UiUserEntity member = createUser(memberName);
        String memberToken = login(memberName, "pass-" + memberName);

        Deployed deployed = deployFormProcess("sec74-c1-" + suffix);
        addMember(deployed.processKey(), member.getId(), "OWNER");

        UUID taskId = startAndGetTask(deployed.processDefinitionId());

        String key = UUID.randomUUID().toString();
        String body = "{\"variables\":[{\"name\":\"x\",\"value\":\"1\",\"type\":\"STRING\"}]}";
        MvcResult first = mockMvc.perform(post("/user-tasks/" + taskId + "/complete")
                        .header("Authorization", "Bearer " + memberToken)
                        .header("Idempotency-Key", key)
                        .content(body).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        String storedBody = first.getResponse().getContentAsString();
        assertThat(storedBody).contains(taskId.toString());

        // Revoke: the actor keeps a VALID credential, but loses the grant.
        // (An extra OWNER first — removing the last OWNER is 409 by design.)
        addMember(deployed.processKey(),
            userRepository.findByUsername("admin").orElseThrow().getId(), "OWNER");
        mockMvc.perform(delete("/processes/" + deployed.processKey() + "/members/" + member.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // Same key + same body → the CURRENT policy denies (403), the stale
        // stored success must NOT be served.
        MvcResult replay = mockMvc.perform(post("/user-tasks/" + taskId + "/complete")
                        .header("Authorization", "Bearer " + memberToken)
                        .header("Idempotency-Key", key)
                        .content(body).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andReturn();
        assertThat(replay.getResponse().getContentAsString()).isNotEqualTo(storedBody);
    }

    // --- Criterion 2: X-On-Behalf-Of is bound into the fingerprint ---

    @Test
    void replay_changedOnBehalfOf_returns422_originalClaim_replays200() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 4);
        UUID systemUserId = createUserViaHttp("sec74sys" + suffix, "SYSTEM");
        String systemKey = createApiKeyForUser(systemUserId);
        UiUserEntity oboA = createUser("sec74oboA" + suffix);
        UiUserEntity oboB = createUser("sec74oboB" + suffix);

        Deployed deployed = deployFormProcess("sec74-c2-" + suffix);
        addMember(deployed.processKey(), systemUserId, "OWNER");
        setGrantsFull(systemUserId, deployed.processKey());
        // Both claimed users are process members, so the live claim check passes
        // for either one — only the fingerprint may distinguish them.
        addMember(deployed.processKey(), oboA.getId(), "OWNER");
        addMember(deployed.processKey(), oboB.getId(), "OWNER");

        UUID taskId = startAndGetTask(deployed.processDefinitionId());

        String key = UUID.randomUUID().toString();
        MvcResult first = mockMvc.perform(post("/user-tasks/" + taskId + "/claim")
                        .header("Authorization", "Bearer " + systemKey)
                        .header("X-On-Behalf-Of", oboA.getUsername())
                        .header("Idempotency-Key", key))
                .andExpect(status().isOk())
                .andReturn();

        // Same key, same (empty) body, DIFFERENT attribution → 422: a different
        // semantic request, must not inherit the other claim's stored response.
        mockMvc.perform(post("/user-tasks/" + taskId + "/claim")
                        .header("Authorization", "Bearer " + systemKey)
                        .header("X-On-Behalf-Of", oboB.getUsername())
                        .header("Idempotency-Key", key))
                .andExpect(status().isUnprocessableEntity());

        // Same key + ORIGINAL claim → 200 replay, byte-identical to the first.
        MvcResult replay = mockMvc.perform(post("/user-tasks/" + taskId + "/claim")
                        .header("Authorization", "Bearer " + systemKey)
                        .header("X-On-Behalf-Of", oboA.getUsername())
                        .header("Idempotency-Key", key))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
    }

    // --- Red-team HOLD-F1: deleted OBO user → start-replay denied ---

    @Test
    void replay_startAfterOboUserDeleted_returns403_notStaleSuccess() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 4);
        UUID systemUserId = createUserViaHttp("sec74st" + suffix, "SYSTEM");
        String systemKey = createApiKeyForUser(systemUserId);
        UiUserEntity obo = createUser("sec74gone" + suffix);

        Deployed deployed = deployFormProcess("sec74-c3-" + suffix);
        addMember(deployed.processKey(), systemUserId, "OWNER");
        setGrantsFull(systemUserId, deployed.processKey());

        String key = UUID.randomUUID().toString();
        String body = "{\"processDefinitionId\":\"" + deployed.processDefinitionId()
            + "\",\"variables\":[]}";
        MvcResult first = mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + systemKey)
                        .header("X-On-Behalf-Of", obo.getUsername())
                        .header("Idempotency-Key", key)
                        .content(body).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        String storedBody = first.getResponse().getContentAsString();

        // The attribution target disappears; the START grant itself is untouched.
        userRepository.delete(obo);

        MvcResult replay = mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + systemKey)
                        .header("X-On-Behalf-Of", obo.getUsername())
                        .header("Idempotency-Key", key)
                        .content(body).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andReturn();
        assertThat(replay.getResponse().getContentAsString()).isNotEqualTo(storedBody);
    }

    // --- helpers (same shapes as OnBehalfOfIntegrationTest / Rel32IdempotencyMutationIT) ---

    private record Deployed(UUID processDefinitionId, String processKey) {}

    private Deployed deployFormProcess(String key) throws Exception {
        String bpmn = Files.readString(
                Paths.get("src/test/files/form-task.bpmn"), StandardCharsets.UTF_8)
            .replace("id=\"form-process\"", "id=\"" + key + "\"");
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn(bpmn);
        MvcResult r = mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        return new Deployed(
            UUID.fromString(mapper.readTree(r.getResponse().getContentAsString()).get("id").asText()),
            mapper.readTree(r.getResponse().getContentAsString()).get("key").asText());
    }

    private UUID startAndGetTask(UUID pdId) throws Exception {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        dto.setVariables(List.of());
        MvcResult started = mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        UUID piId = UUID.fromString(
            mapper.readTree(started.getResponse().getContentAsString()).get("id").asText());
        MvcResult tasks = mockMvc.perform(get("/user-tasks?processInstanceId=" + piId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(
            mapper.readTree(tasks.getResponse().getContentAsString()).get("data").get(0).get("id").asText());
    }

    private UiUserEntity createUser(String username) {
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash("pass-" + username));
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

    private UUID createUserViaHttp(String username, String userType) throws Exception {
        String body = "{\"username\":\"" + username
            + "\",\"fullName\":\"" + username
            + "\",\"email\":\"" + username + "@zorrodev.test"
            + "\",\"role\":\"SUPER_ADMIN\""
            + ",\"active\":true"
            + ",\"password\":\"MyStr0ng!P@ssw0rd\""
            + ",\"userType\":\"" + userType + "\"}";
        MvcResult result = mockMvc.perform(post("/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(body)
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();
        int st = result.getResponse().getStatus();
        if (st == 409) {
            return userRepository.findByUsername(username).orElseThrow().getId();
        }
        if (st != 200 && st != 201) {
            throw new IllegalStateException("createUserViaHttp(" + username + ") failed: " + st
                + " " + result.getResponse().getContentAsString());
        }
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private String createApiKeyForUser(UUID userId) throws Exception {
        MvcResult result = mockMvc.perform(post("/admin/users/" + userId + "/api-key")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isCreated())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("key").asText();
    }

    private void addMember(String processKey, UUID userId, String role) throws Exception {
        mockMvc.perform(post("/processes/" + processKey + "/members")
                        .header("Authorization", "Bearer " + adminToken)
                        .content("{\"userId\":\"" + userId + "\",\"role\":\"" + role + "\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    private void setGrantsFull(UUID userId, String processKey) throws Exception {
        mockMvc.perform(put("/admin/users/" + userId + "/api-key/grants")
                        .header("Authorization", "Bearer " + adminToken)
                        .content("{\"grants\":[{\"processKey\":\"" + processKey + "\",\"full\":true}]}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }
}
