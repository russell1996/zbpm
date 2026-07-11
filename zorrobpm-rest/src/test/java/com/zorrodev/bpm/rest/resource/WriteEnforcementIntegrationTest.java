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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-MT-3: Write enforcement on all mutating endpoints.
 *
 * #1: Non-member starts foreign process → 403
 * #2: OWNER starts own process → 200
 * #3: Non-member cancels foreign instance → 403
 * #9: Reading remains open → 200
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WriteEnforcementIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private PasswordHasher passwordHasher;
    @Autowired private ProcessRepository processRepository;
    @Autowired private ProcessMemberRepository processMemberRepository;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String adminToken;
    private String userToken;
    private UUID processId;
    private String definitionKey = "we-test-process";

    @BeforeAll
    void setup() throws Exception {
        // Create USER-role user (use unique username to avoid conflicts with other tests)
        String uniqueUsername = "we-user-" + UUID.randomUUID().toString().substring(0, 8);
        UiUserEntity user = new UiUserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(uniqueUsername);
        user.setPasswordHash(passwordHasher.hash("user"));
        user.setFullName("WE Test User");
        user.setRole("USER");
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);

        // Promote admin to SUPER_ADMIN (migration runs before bootstrap, so admin gets "ADMIN")
        var admin = userRepository.findByUsername("admin").orElseThrow();
        admin.setRole("SUPER_ADMIN");
        userRepository.save(admin);

        // Login admin
        LoginDTO loginDto = new LoginDTO();
        loginDto.setUsername("admin");
        loginDto.setPassword("admin");
        MvcResult adminResult = mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(loginDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        adminToken = mapper.readValue(adminResult.getResponse().getContentAsString(), AuthResponse.class).getToken();

        // Login user
        LoginDTO userDto = new LoginDTO();
        userDto.setUsername(uniqueUsername);
        userDto.setPassword("user");
        MvcResult userResult = mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(userDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        userToken = mapper.readValue(userResult.getResponse().getContentAsString(), AuthResponse.class).getToken();

        // Create process in registry (not as OWNER of the user)
        ProcessEntity process = new ProcessEntity();
        process.setId(UUID.randomUUID());
        process.setDefinitionKey(definitionKey);
        process.setName("Write Enforcement Test Process");
        process.setCreatedAt(Instant.now());
        processRepository.save(process);
        processId = process.getId();

        // Deploy BPMN so we can start an instance
        String bpmn = new String(Files.readAllBytes(Paths.get("src/test/files/test-we-enforcement.bpmn")), StandardCharsets.UTF_8);
        AddProcessDefinitionDTO addDto = new AddProcessDefinitionDTO();
        addDto.setBpmn(bpmn);
        mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(addDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    // --- #1: Non-member starts foreign process → 403 ---

    @Test
    void criterion1_nonMemberStartsForeignProcess_returns403() throws Exception {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionKey(definitionKey);

        mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + userToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    // --- #2: OWNER starts own process → 200 ---

    @Test
    void criterion2_ownerStartsOwnProcess_returns200() throws Exception {
        // Admin is SUPER_ADMIN (auto-owner via backfill), so should work
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionKey(definitionKey);

        mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    // --- #3: Non-member cancels foreign instance → 403 ---

    @Test
    void criterion3_nonMemberCancelsForeignInstance_returns403() throws Exception {
        // Start as admin
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionKey(definitionKey);
        MvcResult result = mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        String instanceId = mapper.readTree(result.getResponse().getContentAsString()).get("id").asText();

        // Cancel as non-member → 403
        mockMvc.perform(post("/process-instances/" + instanceId + "/cancel")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    // --- #9: Reading remains open (no membership required) ---

    @Test
    void criterion9_readingWithoutMembership_stillWorks() throws Exception {
        mockMvc.perform(get("/process-definitions")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/process-instances")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());
    }
}
