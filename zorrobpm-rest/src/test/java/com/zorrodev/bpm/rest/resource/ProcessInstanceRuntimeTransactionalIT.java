package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.engine.entity.ApiKeyEntity;
import com.zorrodev.bpm.engine.entity.ApiKeyGrantEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ApiKeyGrantRepository;
import com.zorrodev.bpm.engine.repository.ApiKeyRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.KeyHasher;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import com.zorrodev.bpm.engine.service.AuditLogService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProcessInstanceRuntimeTransactionalIT {

    @Autowired MockMvc mockMvc;
    @Autowired UiUserRepository userRepository;
    @Autowired ProcessRepository processRepository;
    @Autowired ProcessMemberRepository processMemberRepository;
    @Autowired ApiKeyRepository apiKeyRepository;
    @Autowired ApiKeyGrantRepository apiKeyGrantRepository;
    @Autowired PasswordHasher passwordHasher;
    @Autowired ProcessInstanceRepository processInstanceRepository;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean AuditLogService auditLogService;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String adminToken;
    private String processKey;
    private UUID apiKeyUserId;
    private String apiKey;

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin", "admin");
        // create service user for START via API key with X-On-Behalf-Of
        apiKeyUserId = UUID.randomUUID();
        UiUserEntity u = new UiUserEntity();
        u.setId(apiKeyUserId);
        u.setUsername("pi-tx-" + apiKeyUserId.toString().substring(0, 8));
        u.setPasswordHash(passwordHasher.hash("pass"));
        u.setFullName("PI TX");
        u.setRole("USER");
        u.setActive(true);
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        userRepository.save(u);
        // WO-SEC-64 HOLD (S-RBAC-3, existence-гейт): фикстурные OBO-имена обязаны
        // существовать в БД, иначе fail-closed 404 (а не молчаливый [claimed]).
        for (String name : new String[]{"claimed-user", "happy-user"}) {
            UiUserEntity e = new UiUserEntity();
            e.setId(UUID.randomUUID());
            e.setUsername(name);
            e.setPasswordHash(passwordHasher.hash("pass"));
            e.setFullName(name);
            e.setRole("USER");
            e.setActive(true);
            e.setCreatedAt(Instant.now());
            e.setUpdatedAt(Instant.now());
            try {
                userRepository.save(e);
            } catch (Exception ex) {
                // уже есть от прошлого прогона shared-H2 — идемпотентный сетап
            }
        }
    }

    private String deployProcessAndGrant() throws Exception {
        String bpmnRaw = java.nio.file.Files.readString(java.nio.file.Paths.get("src/test/files/process1.bpmn"));
        String randomKey = "pi-tx-" + UUID.randomUUID().toString().substring(0, 8);
        String bpmn = bpmnRaw.replace("process1", randomKey).replace("Process 1", randomKey);
        com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO addDto = new com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO();
        addDto.setBpmn(bpmn);
        com.fasterxml.jackson.databind.JsonNode deploy = mapper.readTree(mockMvc.perform(post("/process-definitions")
                .header("Authorization", "Bearer " + adminToken)
                .content(mapper.writeValueAsString(addDto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String key = deploy.get("key").asText();
        // add OWNER membership directly via DB
        var proc = processRepository.findByDefinitionKey(key).orElseThrow();
        ProcessMemberEntity m = new ProcessMemberEntity();
        m.setProcessId(proc.getId());
        m.setUserId(apiKeyUserId);
        m.setRole("OWNER");
        m.setAddedAt(Instant.now());
        processMemberRepository.save(m);
        // create API key directly via DB (avoid HTTP flake)
        if (apiKey == null) {
            String raw = "zbpm_sk_test_" + UUID.randomUUID().toString().replace("-", "");
            String hash = KeyHasher.sha256(raw);
            ApiKeyEntity k = new ApiKeyEntity();
            k.setId(UUID.randomUUID());
            k.setOwnerUserId(apiKeyUserId);
            k.setKeyHash(hash);
            k.setPrefix(raw.substring(0, Math.min(16, raw.length())));
            k.setCreatedAt(Instant.now());
            apiKeyRepository.save(k);
            apiKey = raw;
            // grant START on this process
            ApiKeyGrantEntity g = new ApiKeyGrantEntity();
            g.setApiKeyId(k.getId());
            g.setProcessId(proc.getId());
            g.setPermissions("START");
            g.setFull(false);
            apiKeyGrantRepository.save(g);
        } else {
            // ensure grant for this new key (reuse same apiKey id)
            var existingKey = apiKeyRepository.findByOwnerUserId(apiKeyUserId).orElseThrow();
            // add grant for new process if not exists
            boolean hasGrant = apiKeyGrantRepository.findByApiKeyId(existingKey.getId()).stream()
                .anyMatch(g -> g.getProcessId().equals(proc.getId()));
            if (!hasGrant) {
                ApiKeyGrantEntity g = new ApiKeyGrantEntity();
                g.setApiKeyId(existingKey.getId());
                g.setProcessId(proc.getId());
                g.setPermissions("START");
                g.setFull(false);
                apiKeyGrantRepository.save(g);
            }
        }
        processKey = key;
        return key;
    }

    @Test
    void startProcessInstance_auditFails_initiatorNotPersisted() throws Exception {
        deployProcessAndGrant();
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM process_instances", Long.class);
        doThrow(new RuntimeException("audit fail")).when(auditLogService).record(any(), any(), any(), any(), any());
        // also stub 4-arg overload used when onBehalfOf null? we cover both
        doThrow(new RuntimeException("audit fail")).when(auditLogService).record(any(), any(), any(), any());

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionKey(processKey);
        dto.setVariables(java.util.List.of());

        mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + apiKey)
                .header("X-On-Behalf-Of", "claimed-user")
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().is5xxServerError());

        long after = jdbc.queryForObject("SELECT COUNT(*) FROM process_instances", Long.class);
        assertThat(after).as("transaction must roll back on audit failure").isEqualTo(before);
    }

    @Test
    void startProcessInstance_happy_initiatorPersisted() throws Exception {
        deployProcessAndGrant();
        org.mockito.Mockito.reset(auditLogService);
        // audit succeeds (lenient mock returns void)
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionKey(processKey);
        dto.setVariables(java.util.List.of());

        MvcResult res = mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + apiKey)
                .header("X-On-Behalf-Of", "happy-user")
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated()).andReturn();
        String piId = mapper.readTree(res.getResponse().getContentAsString()).get("id").asText();
        String initiator = jdbc.queryForObject("SELECT initiator FROM process_instances WHERE id = ?", String.class, UUID.fromString(piId));
        assertThat(initiator).isEqualTo("[claimed] happy-user");
    }

    private String login(String u, String p) throws Exception {
        var dto = new com.zorrodev.bpm.contract.dto.LoginDTO();
        dto.setUsername(u); dto.setPassword(p);
        MvcResult r = mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer").content(mapper.writeValueAsString(dto)).contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk()).andReturn();
        return mapper.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }
}
