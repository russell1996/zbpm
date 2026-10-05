package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-ACL-22: OWNER and DESIGNER cancel instances of their own process (V11 —
 * full Spring context, real filter chain, real authz, no mocks).
 *
 * <p>Setup mirrors WriteEnforcementIntegrationTest (unique usernames per class,
 * P-8): three USER-role users become OWNER / DESIGNER / VIEWER of the
 * cancel-test-process registry row; each test starts its OWN instance as
 * admin (cancel is terminal — sharing one instance would 409 the second
 * test, P-8/P-10).
 *
 * <p>POF: remove DELETE_PROCESS from OWNER/DESIGNER in ROLE_RIGHTS →
 * owner/designer tests go 403 RED, viewer test stays GREEN (it asserts the
 * unchanged deny).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CancelProcessInstanceAuthzIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private PasswordHasher passwordHasher;
    @Autowired private ProcessRepository processRepository;
    @Autowired private ProcessMemberRepository processMemberRepository;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String adminToken;
    private String ownerToken;
    private String designerToken;
    private String viewerToken;

    @BeforeAll
    void setup() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UiUserEntity owner = createUser("acl22-owner-" + suffix, "ownerpass");
        UiUserEntity designer = createUser("acl22-designer-" + suffix, "designerpass");
        UiUserEntity viewer = createUser("acl22-viewer-" + suffix, "viewerpass");

        var admin = userRepository.findByUsername("admin").orElseThrow();
        admin.setRole("SUPER_ADMIN");
        userRepository.save(admin);

        adminToken = login("admin", "admin");
        ownerToken = login(owner.getUsername(), "ownerpass");
        designerToken = login(designer.getUsername(), "designerpass");
        viewerToken = login(viewer.getUsername(), "viewerpass");

        // Registry row for the fixture key (find-or-create: CancelProcessInstanceTest
        // deploys the same fixture and may own the row in the shared DB).
        ProcessEntity process = processRepository.findByDefinitionKey("cancel-test-process")
            .orElseGet(() -> {
                ProcessEntity p = new ProcessEntity();
                p.setId(UUID.randomUUID());
                p.setDefinitionKey("cancel-test-process");
                p.setName("Cancel Test Process");
                p.setCreatedAt(Instant.now());
                return processRepository.save(p);
            });

        addMembership(process.getId(), owner.getId(), "OWNER");
        addMembership(process.getId(), designer.getId(), "DESIGNER");
        addMembership(process.getId(), viewer.getId(), "VIEWER");

        String bpmn = Files.readString(
            Paths.get("src/test/files/test-cancel-process.bpmn"), StandardCharsets.UTF_8);
        AddProcessDefinitionDTO addDto = new AddProcessDefinitionDTO();
        addDto.setBpmn(bpmn);
        mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(addDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());
    }

    // --- WO-ACL-22 criterion 4: OWNER cancels own instance → 202 ---

    @Test
    void ownerCancelsOwnInstance_returns202() throws Exception {
        String instanceId = startInstance();
        mockMvc.perform(post("/process-instances/" + instanceId + "/cancel")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isAccepted());
    }

    // --- WO-ACL-22: DESIGNER cancels own instance → 202 ---

    @Test
    void designerCancelsOwnInstance_returns202() throws Exception {
        String instanceId = startInstance();
        mockMvc.perform(post("/process-instances/" + instanceId + "/cancel")
                        .header("Authorization", "Bearer " + designerToken))
                .andExpect(status().isAccepted());
    }

    // --- WO-ACL-22 criterion 5: VIEWER still denied → 403 (regression) ---

    @Test
    void viewerCancelsOwnInstance_returns403() throws Exception {
        String instanceId = startInstance();
        mockMvc.perform(post("/process-instances/" + instanceId + "/cancel")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden());
    }

    private String startInstance() throws Exception {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionKey("cancel-test-process");
        MvcResult result = mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private UiUserEntity createUser(String username, String password) {
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash(password));
        user.setFullName(username);
        user.setRole("USER");
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user);
    }

    private void addMembership(UUID processId, UUID userId, String role) {
        ProcessMemberEntity membership = new ProcessMemberEntity();
        membership.setProcessId(processId);
        membership.setUserId(userId);
        membership.setRole(role);
        membership.setAddedBy(userId);
        membership.setAddedAt(Instant.now());
        processMemberRepository.save(membership);
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
