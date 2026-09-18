package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.*;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-API-1, кластер 3 (API-1/API-7): HTTP-коды create/cancel + атомарность initiator.
 *
 * <p>Всё — через полный Spring-контекст (V11).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Api1StatusCodesIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String adminToken;

    @BeforeAll
    void setup() throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername("admin");
        dto.setPassword("admin");
        MvcResult r = mockMvc.perform(post("/auth/login")
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andReturn();
        adminToken = mapper.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    private UUID deployProcess(String key) throws Exception {
        String bpmn = new String(Files.readAllBytes(Paths.get("src/test/files/form-task.bpmn")))
                .replace("form-process", key).replace("Form Process", key);
        AddProcessDefinitionDTO addDto = new AddProcessDefinitionDTO();
        addDto.setBpmn(bpmn);
        MvcResult deploy = mockMvc.perform(post("/process-definitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(addDto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated()).andReturn();
        // WO-API-1: деплой — тоже create (201 + Location уже проверены продом)
        assertThat(deploy.getResponse().getHeader("Location")).isNotNull();
        return UUID.fromString(mapper.readTree(deploy.getResponse().getContentAsString()).get("id").asText());
    }

    // ── Доп. критерий: create → 201 + Location ───────────────────────

    @Test
    void createProcess_returns201_withLocation() throws Exception {
        UUID pdId = deployProcess("api1-st-" + UUID.randomUUID().toString().substring(0, 8));
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        dto.setVariables(List.of());
        MvcResult r = mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andReturn();
        String id = mapper.readTree(r.getResponse().getContentAsString()).get("id").asText();
        assertThat(r.getResponse().getHeader("Location")).contains(id);
    }

    @Test
    void createUser_returns201_withLocation() throws Exception {
        String uname = "api1-u-" + UUID.randomUUID().toString().substring(0, 8);
        String body = "{\"username\":\"" + uname + "\",\"fullName\":\"" + uname
                + "\",\"email\":\"" + uname + "@zorrodev.test\",\"role\":\"USER\",\"active\":true,"
                + "\"password\":\"MyStr0ng!P@ssw0rd\",\"userType\":\"HUMAN\"}";
        MvcResult r = mockMvc.perform(post("/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", "api1-" + UUID.randomUUID())
                        .content(body).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andReturn();
        String id = mapper.readTree(r.getResponse().getContentAsString()).get("id").asText();
        assertThat(r.getResponse().getHeader("Location")).contains(id);
    }

    // ── Доп. критерий: cancel → 202, не голый 200 ────────────────────

    @Test
    void cancel_returns202() throws Exception {
        UUID pdId = deployProcess("api1-cx-" + UUID.randomUUID().toString().substring(0, 8));
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        dto.setVariables(List.of());
        MvcResult started = mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + adminToken)
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated()).andReturn();
        String piId = mapper.readTree(started.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post("/process-instances/" + piId + "/cancel")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isAccepted());
    }

    // ── Доп. критерий: initiator атомарно при создании ───────────────

    @Autowired com.zorrodev.bpm.engine.repository.UiUserRepository userRepository;
    @Autowired com.zorrodev.bpm.engine.security.PasswordHasher passwordHasher;
    @Autowired com.zorrodev.bpm.engine.repository.ApiKeyRepository apiKeyRepository;
    @Autowired com.zorrodev.bpm.engine.repository.ApiKeyGrantRepository apiKeyGrantRepository;
    @Autowired com.zorrodev.bpm.engine.repository.ProcessRepository processRepository;
    @Autowired com.zorrodev.bpm.engine.repository.ProcessMemberRepository processMemberRepository;

    @Test
    void start_withOnBehalfOf_initiatorPersistedAtomically() throws Exception {
        String pkey = "api1-in-" + UUID.randomUUID().toString().substring(0, 8);
        UUID pdId = deployProcess(pkey);
        // service-ключ с грантом START (OBO принимают только API-ключи)
        UUID uid = UUID.randomUUID();
        var u = new com.zorrodev.bpm.engine.entity.UiUserEntity();
        u.setId(uid);
        u.setUsername("api1-svc-" + uid.toString().substring(0, 8));
        u.setPasswordHash(passwordHasher.hash("pass"));
        u.setFullName("API1 SVC");
        u.setRole("USER");
        u.setActive(true);
        u.setCreatedAt(java.time.Instant.now());
        u.setUpdatedAt(java.time.Instant.now());
        userRepository.save(u);
        String raw = "zbpm_sk_test_" + UUID.randomUUID().toString().replace("-", "");
        var k = new com.zorrodev.bpm.engine.entity.ApiKeyEntity();
        k.setId(UUID.randomUUID());
        k.setOwnerUserId(uid);
        k.setKeyHash(com.zorrodev.bpm.engine.security.KeyHasher.sha256(raw));
        k.setPrefix(raw.substring(0, 16));
        k.setCreatedAt(java.time.Instant.now());
        apiKeyRepository.save(k);
        // registry-процесс: deploy-эндпоинт регистрирует его при деплое —
        // грант маппится через definitionKey→process (прецедент
        // ProcessInstanceRuntimeTransactionalIT: OWNER-мемберство + грант).
        var proc = processRepository.findByDefinitionKey(pkey).orElseThrow();
        var m = new com.zorrodev.bpm.engine.entity.ProcessMemberEntity();
        m.setProcessId(proc.getId());
        m.setUserId(uid);
        m.setRole("OWNER");
        m.setAddedAt(java.time.Instant.now());
        processMemberRepository.save(m);
        var g = new com.zorrodev.bpm.engine.entity.ApiKeyGrantEntity();
        g.setApiKeyId(k.getId());
        g.setProcessId(proc.getId());
        g.setPermissions("START");
        g.setFull(false);
        apiKeyGrantRepository.save(g);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        dto.setVariables(List.of());
        MvcResult started = mockMvc.perform(post("/process-instances")
                        .header("Authorization", "Bearer " + raw)
                        .header("X-On-Behalf-Of", "admin")
                        .content(mapper.writeValueAsString(dto))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated()).andReturn();
        UUID piId = UUID.fromString(mapper.readTree(started.getResponse().getContentAsString()).get("id").asText());

        // initiator записан тем же вызовом (не отдельным патчем после):
        // значение видно сразу, без второго запроса на запись.
        String initiator = jdbcTemplate.queryForObject(
                "SELECT initiator FROM process_instances WHERE id = ?", String.class, piId);
        assertThat(initiator).isEqualTo("[claimed] admin");
    }
}
