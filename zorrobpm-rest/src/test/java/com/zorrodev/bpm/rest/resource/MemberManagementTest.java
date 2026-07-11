package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
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

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * WO-MT-5: Member management + deploy→owner.
 * Full-context tests (V11) through real filter chain.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberManagementTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private ProcessRepository processRepository;
    @Autowired private ProcessMemberRepository processMemberRepository;
    @Autowired private PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String ownerToken;
    private UUID ownerId;
    private String designerToken;
    private UUID designerId;
    private String nonMemberToken;
    private UUID nonMemberId;
    private String adminToken;
    private UUID adminId;
    private String bpmn;
    private String bpmnKey;

    @BeforeAll
    void setup() throws Exception {
        bpmn = new String(Files.readAllBytes(Paths.get("src/test/files/process1.bpmn")));
        bpmnKey = "process1"; // matches id in process1.bpmn

        // Owner
        UiUserEntity owner = new UiUserEntity();
        ownerId = UUID.randomUUID();
        owner.setId(ownerId);
        owner.setUsername("mt5-owner");
        owner.setPasswordHash(passwordHasher.hash("pass"));
        owner.setFullName("MT5 Owner");
        owner.setRole("USER");
        owner.setActive(true);
        owner.setCreatedAt(Instant.now());
        owner.setUpdatedAt(Instant.now());
        userRepository.save(owner);

        // Designer (will be added as member)
        UiUserEntity designer = new UiUserEntity();
        designerId = UUID.randomUUID();
        designer.setId(designerId);
        designer.setUsername("mt5-designer");
        designer.setPasswordHash(passwordHasher.hash("pass"));
        designer.setFullName("MT5 Designer");
        designer.setRole("USER");
        designer.setActive(true);
        designer.setCreatedAt(Instant.now());
        designer.setUpdatedAt(Instant.now());
        userRepository.save(designer);

        // Non-member
        UiUserEntity nonMember = new UiUserEntity();
        nonMemberId = UUID.randomUUID();
        nonMember.setId(nonMemberId);
        nonMember.setUsername("mt5-nonmember");
        nonMember.setPasswordHash(passwordHasher.hash("pass"));
        nonMember.setFullName("MT5 Non-Member");
        nonMember.setRole("USER");
        nonMember.setActive(true);
        nonMember.setCreatedAt(Instant.now());
        nonMember.setUpdatedAt(Instant.now());
        userRepository.save(nonMember);

        // Super admin
        UiUserEntity admin = new UiUserEntity();
        adminId = UUID.randomUUID();
        admin.setId(adminId);
        admin.setUsername("mt5-admin");
        admin.setPasswordHash(passwordHasher.hash("pass"));
        admin.setFullName("MT5 Admin");
        admin.setRole("SUPER_ADMIN");
        admin.setActive(true);
        admin.setCreatedAt(Instant.now());
        admin.setUpdatedAt(Instant.now());
        userRepository.save(admin);

        // Login all users
        ownerToken = login("mt5-owner", "pass");
        designerToken = login("mt5-designer", "pass");
        nonMemberToken = login("mt5-nonmember", "pass");
        adminToken = login("mt5-admin", "pass");
    }

    private LoginDTO loginDto(String username, String password) {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        return dto;
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(loginDto(username, password)))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class).getToken();
    }

    private String deployBpmn(String token, String bpmnContent) throws Exception {
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn(bpmnContent);
        MvcResult result = mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + token)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    /** Generate a BPMN with a unique key to avoid H2 state leakage between tests. */
    private String uniqueBpmn() {
        String key = "mt5_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return bpmn.replace("id=\"process1\"", "id=\"" + key + "\"")
                   .replace("name=\"Process 1\"", "name=\"" + key + "\"")
                   .replace("process id=\"process1\"", "process id=\"" + key + "\"");
    }

    private String extractKeyFromBpmn(String bpmnContent) {
        int idx = bpmnContent.indexOf("process id=\"");
        if (idx < 0) throw new RuntimeException("Cannot find process id in BPMN");
        int start = idx + "process id=\"".length();
        int end = bpmnContent.indexOf("\"", start);
        return bpmnContent.substring(start, end);
    }

    // ==================== Criterion #1: deploy → deployer becomes OWNER ====================

    @Test
    void criterion1_deployCreatesOwner() throws Exception {
        String testBpmn = uniqueBpmn();
        String key = extractKeyFromBpmn(testBpmn);
        deployBpmn(ownerToken, testBpmn);

        var process = processRepository.findByDefinitionKey(key);
        assertTrue(process.isPresent(), "Process should exist after deploy");

        var members = processMemberRepository.findByProcessId(process.get().getId());
        long ownerCount = members.stream().filter(m -> "OWNER".equals(m.getRole()) && ownerId.equals(m.getUserId())).count();
        assertEquals(1, ownerCount, "Deployer should be OWNER");
    }

    // ==================== Criterion #2: redeploy does NOT change ownership ====================

    @Test
    void criterion2_redeployDoesNotStealOwnership() throws Exception {
        String testBpmn = uniqueBpmn();
        String key = extractKeyFromBpmn(testBpmn);
        deployBpmn(ownerToken, testBpmn);

        var process = processRepository.findByDefinitionKey(key).orElseThrow();

        deployBpmn(adminToken, testBpmn);

        var members = processMemberRepository.findByProcessId(process.getId());
        long originalOwner = members.stream()
            .filter(m -> "OWNER".equals(m.getRole()) && ownerId.equals(m.getUserId()))
            .count();
        assertEquals(1, originalOwner, "Original deployer should still be OWNER");

        long adminOwner = members.stream()
            .filter(m -> "OWNER".equals(m.getRole()) && adminId.equals(m.getUserId()))
            .count();
        assertEquals(0, adminOwner, "Redeployer should NOT become OWNER");
    }

    @Test
    void criterion3_ownerAddsDesigner() throws Exception {
        String testBpmn = uniqueBpmn();
        String key = extractKeyFromBpmn(testBpmn);
        deployBpmn(ownerToken, testBpmn);

        mockMvc.perform(post("/processes/" + key + "/members")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + designerId + "\",\"role\":\"DESIGNER\"}"))
                .andExpect(status().isOk());

        var process = processRepository.findByDefinitionKey(key).orElseThrow();
        var members = processMemberRepository.findByProcessId(process.getId());
        boolean hasDesigner = members.stream()
            .anyMatch(m -> "DESIGNER".equals(m.getRole()) && designerId.equals(m.getUserId()));
        assertTrue(hasDesigner, "DESIGNER member should exist after OWNER adds them");
    }

    @Test
    void criterion4_nonOwnerGets403() throws Exception {
        String testBpmn = uniqueBpmn();
        String key = extractKeyFromBpmn(testBpmn);
        deployBpmn(ownerToken, testBpmn);

        mockMvc.perform(post("/processes/" + key + "/members")
                        .header("Authorization", "Bearer " + nonMemberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + designerId + "\",\"role\":\"DESIGNER\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void criterion5_cannotRemoveLastOwner() throws Exception {
        String testBpmn = uniqueBpmn();
        String key = extractKeyFromBpmn(testBpmn);
        deployBpmn(ownerToken, testBpmn);

        mockMvc.perform(delete("/processes/" + key + "/members/" + ownerId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isConflict());
    }

    @Test
    void criterion6_superAdminManagesAny() throws Exception {
        String testBpmn = uniqueBpmn();
        String key = extractKeyFromBpmn(testBpmn);
        deployBpmn(ownerToken, testBpmn);

        mockMvc.perform(post("/processes/" + key + "/members")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + designerId + "\",\"role\":\"DESIGNER\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/processes/" + key + "/members")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    void criterion8_proofOfFailure_deployCreatesOwnership() throws Exception {
        String testBpmn = uniqueBpmn();
        String key = extractKeyFromBpmn(testBpmn);
        deployBpmn(ownerToken, testBpmn);

        mockMvc.perform(post("/processes/" + key + "/members")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + designerId + "\",\"role\":\"DESIGNER\"}"))
                .andExpect(status().isOk());
    }
}
