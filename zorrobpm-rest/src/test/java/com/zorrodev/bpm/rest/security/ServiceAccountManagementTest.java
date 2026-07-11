package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.ServiceAccountRepository;
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

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-MT-4: Service Account API key management.
 * Proof-of-failure: POST /processes/{key}/service-accounts on current code → 404.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServiceAccountManagementTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UiUserRepository userRepository;
    @Autowired private ProcessRepository processRepository;
    @Autowired private ProcessMemberRepository processMemberRepository;
    @Autowired private ServiceAccountRepository serviceAccountRepository;
    @Autowired private PasswordHasher passwordHasher;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String ownerToken;
    private UUID ownerId;
    private String processKey = "mt4-test-process";

    @BeforeAll
    void setup() throws Exception {
        UiUserEntity owner = new UiUserEntity();
        ownerId = UUID.randomUUID();
        owner.setId(ownerId);
        owner.setUsername("mt4-owner");
        owner.setPasswordHash(passwordHasher.hash("pass"));
        owner.setFullName("MT4 Owner");
        owner.setRole("USER");
        owner.setActive(true);
        owner.setCreatedAt(Instant.now());
        owner.setUpdatedAt(Instant.now());
        userRepository.save(owner);

        ProcessEntity process = new ProcessEntity();
        process.setId(UUID.randomUUID());
        process.setDefinitionKey(processKey);
        process.setName("MT4 Test Process");
        process.setCreatedAt(Instant.now());
        processRepository.save(process);

        ProcessMemberEntity membership = new ProcessMemberEntity();
        membership.setProcessId(process.getId());
        membership.setUserId(ownerId);
        membership.setRole("OWNER");
        membership.setAddedAt(Instant.now());
        processMemberRepository.save(membership);

        LoginDTO dto = new LoginDTO();
        dto.setUsername("mt4-owner");
        dto.setPassword("pass");
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        ownerToken = mapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class).getToken();
    }

    /**
     * Proof-of-failure GREEN: endpoint exists, returns 200 for OWNER.
     * RED was 404 (endpoint did not exist before implementation).
     */
    @Test
    void criterion9_proofOfFailure_green() throws Exception {
        mockMvc.perform(post("/processes/" + processKey + "/service-accounts")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"test-sa\",\"permissions\":[\"START\"]}"))
                .andExpect(status().isOk());
    }
}
