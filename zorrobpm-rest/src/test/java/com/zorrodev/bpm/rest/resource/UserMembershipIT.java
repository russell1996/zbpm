package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * WO-MT-9c: IT for GET /admin/users/{id}/memberships endpoint.
 * Covers: super-admin 200, non-super 403, add→list→remove flow.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserMembershipIT {

    @Autowired MockMvc mockMvc;
    @Autowired UiUserRepository userRepository;
    @Autowired ProcessRepository processRepository;
    @Autowired ProcessMemberRepository processMemberRepository;
    @Autowired PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String superAdminToken;
    private String regularUserToken;
    private UUID regularUserId;
    private String processKey;

    @BeforeAll
    void setup() throws Exception {
        // Create super-admin (via DB — the 'admin' user from Liquibase)
        superAdminToken = loginAndGetToken("admin", "admin");

        // Create regular user
        regularUserId = createUser("mt9c-user", "USER");
        regularUserToken = loginAndGetToken("mt9c-user", "pass");

        // Deploy a process
        String bpmn = new String(Files.readAllBytes(Paths.get("src/test/files/process1.bpmn")));
        processKey = "mt9c-" + UUID.randomUUID().toString().substring(0, 8);
        String testBpmn = bpmn.replace("id=\"process1\"", "id=\"" + processKey + "\"")
                              .replace("name=\"Process 1\"", "name=\"" + processKey + "\"")
                              .replace("process id=\"process1\"", "process id=\"" + processKey + "\"");
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn(testBpmn);
        mockMvc.perform(post("/process-definitions")
                .header("Authorization", "Bearer " + superAdminToken)
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated());
    }

    // --- Criterion #1: super-admin → 200 + list matches memberships ---

    @Test
    void superAdmin_getMemberships_returns200() throws Exception {
        // Add regular user as OWNER of the process
        mockMvc.perform(post("/processes/" + processKey + "/members")
                .header("Authorization", "Bearer " + superAdminToken)
                .content("{\"userId\":\"" + regularUserId + "\",\"role\":\"OWNER\"}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());

        // GET memberships → should contain the added membership
        MvcResult result = mockMvc.perform(get("/admin/users/" + regularUserId + "/memberships")
                .header("Authorization", "Bearer " + superAdminToken))
            .andExpect(status().isOk())
            .andReturn();

        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains(processKey), "Membership list should contain the process key");
        assertTrue(body.contains("OWNER"), "Membership list should show role");
    }

    // --- Criterion #2: non-super → 403 ---

    @Test
    void nonSuperAdmin_gets403() throws Exception {
        mockMvc.perform(get("/admin/users/" + regularUserId + "/memberships")
                .header("Authorization", "Bearer " + regularUserToken))
            .andExpect(status().isForbidden());
    }

    // --- Criterion #3: add→list→remove full flow ---

    @Test
    void addListRemove_fullFlow() throws Exception {
        // Add member
        mockMvc.perform(post("/processes/" + processKey + "/members")
                .header("Authorization", "Bearer " + superAdminToken)
                .content("{\"userId\":\"" + regularUserId + "\",\"role\":\"DESIGNER\"}")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());

        // List → should contain the membership
        MvcResult listResult = mockMvc.perform(get("/admin/users/" + regularUserId + "/memberships")
                .header("Authorization", "Bearer " + superAdminToken))
            .andExpect(status().isOk())
            .andReturn();
        String listBody = listResult.getResponse().getContentAsString();
        assertTrue(listBody.contains(processKey), "Should contain the process key after add");

        // Remove member
        mockMvc.perform(delete("/processes/" + processKey + "/members/" + regularUserId)
                .header("Authorization", "Bearer " + superAdminToken))
            .andExpect(status().isOk());

        // List → should be empty now
        MvcResult listAfterRemove = mockMvc.perform(get("/admin/users/" + regularUserId + "/memberships")
                .header("Authorization", "Bearer " + superAdminToken))
            .andExpect(status().isOk())
            .andReturn();
        String afterBody = listAfterRemove.getResponse().getContentAsString();
        assertFalse(afterBody.contains(processKey), "Membership should be gone after remove");
    }

    // --- Helpers ---

    private String loginAndGetToken(String username, String password) throws Exception {
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

    private UUID createUser(String username, String role) {
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash("pass"));
        user.setFullName(username);
        user.setRole(role);
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user).getId();
    }
}
