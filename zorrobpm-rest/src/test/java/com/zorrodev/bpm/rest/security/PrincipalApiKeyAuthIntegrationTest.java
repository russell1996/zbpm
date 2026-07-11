package com.zorrodev.bpm.rest.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ServiceAccountEntity;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.ServiceAccountRepository;
import com.zorrodev.bpm.engine.security.KeyHasher;
import com.zorrodev.bpm.engine.security.TokenService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-MT-2: API-key auth + Principal + AuthorizationService.
 * V11: Full Spring context + real filter chain.
 */
@ActiveProfiles("test")
@SpringBootTest(classes = com.zorrodev.bpm.rest.resource.TestMain.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PrincipalApiKeyAuthIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ServiceAccountRepository serviceAccountRepository;
    @Autowired private ProcessRepository processRepository;
    @Autowired private TokenService tokenService;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String adminToken;
    private UUID processId;
    private UUID saId;
    private String rawApiKey;

    @BeforeAll
    void setup() throws Exception {
        LoginDTO loginDto = new LoginDTO();
        loginDto.setUsername("admin");
        loginDto.setPassword("admin");
        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(loginDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        adminToken = mapper.readValue(loginResult.getResponse().getContentAsString(), AuthResponse.class).getToken();

        ProcessEntity process = new ProcessEntity();
        process.setId(UUID.randomUUID());
        process.setDefinitionKey("mt2-test-process");
        process.setName("MT2 Test Process");
        process.setCreatedAt(Instant.now());
        processRepository.save(process);
        processId = process.getId();

        rawApiKey = "zbpm_sk_" + UUID.randomUUID().toString().replace("-", "");
        saId = UUID.randomUUID();
        ServiceAccountEntity sa = new ServiceAccountEntity();
        sa.setId(saId);
        sa.setProcessId(processId);
        sa.setName("test-sa");
        sa.setKeyHash(KeyHasher.sha256(rawApiKey));
        sa.setPrefix(rawApiKey.substring(0, 16));
        sa.setCreatedAt(Instant.now());
        serviceAccountRepository.save(sa);
    }

    // --- #1: valid zbpm_sk_ → not 401 ---

    @Test
    void criterion1_validApiKey_grantsAccess() throws Exception {
        int status = mockMvc.perform(get("/process-instances")
                        .header("Authorization", "Bearer " + rawApiKey))
                .andReturn().getResponse().getStatus();
        assertThat(status).isNotEqualTo(401);
    }

    // --- #2: revoked key → 401 ---

    @Test
    void criterion2_revokedKey_returns401() throws Exception {
        ServiceAccountEntity sa = serviceAccountRepository.findById(saId).orElseThrow();
        sa.setRevokedAt(Instant.now());
        serviceAccountRepository.save(sa);

        mockMvc.perform(get("/process-instances")
                        .header("Authorization", "Bearer " + rawApiKey))
                .andExpect(status().isUnauthorized());

        sa.setRevokedAt(null);
        serviceAccountRepository.save(sa);
    }

    // --- #3: expired key → 401 ---

    @Test
    void criterion3_expiredKey_returns401() throws Exception {
        ServiceAccountEntity sa = serviceAccountRepository.findById(saId).orElseThrow();
        sa.setExpiresAt(Instant.now().minusSeconds(3600));
        serviceAccountRepository.save(sa);

        mockMvc.perform(get("/process-instances")
                        .header("Authorization", "Bearer " + rawApiKey))
                .andExpect(status().isUnauthorized());

        sa.setExpiresAt(null);
        serviceAccountRepository.save(sa);
    }

    // --- #4: unknown key → 401 ---

    @Test
    void criterion4_unknownKey_returns401() throws Exception {
        mockMvc.perform(get("/process-instances")
                        .header("Authorization", "Bearer zbpm_sk_unknownkey123456"))
                .andExpect(status().isUnauthorized());
    }

    // --- #5: JWT still works ---

    @Test
    void criterion5_jwtStillWorks() throws Exception {
        mockMvc.perform(get("/auth/me")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    // --- #8: last_used_at updated ---

    @Test
    void criterion8_lastUsedAtUpdated() throws Exception {
        ServiceAccountEntity saBefore = serviceAccountRepository.findById(saId).orElseThrow();
        Instant before = saBefore.getLastUsedAt();

        mockMvc.perform(get("/process-instances")
                        .header("Authorization", "Bearer " + rawApiKey));

        ServiceAccountEntity saAfter = serviceAccountRepository.findById(saId).orElseThrow();
        if (before == null) {
            assertThat(saAfter.getLastUsedAt()).isNotNull();
        } else {
            assertThat(saAfter.getLastUsedAt()).isAfterOrEqualTo(before);
        }
    }
}
