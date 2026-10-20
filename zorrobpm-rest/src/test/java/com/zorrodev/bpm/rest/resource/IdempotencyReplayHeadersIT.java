package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.IdempotencyRecord;
import com.zorrodev.bpm.engine.repository.IdempotencyRecordRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-AUDIT-14 (F-16), end-to-end half: an idempotent REPLAY must be the same
 * answer as the original response — including the {@code Location} header.
 *
 * <p>Before the fix only status/body/contentType were persisted, so the
 * replay answered {@code 201} with the same body (the same instance id) but
 * WITHOUT {@code Location}: a client that follows the header of the first
 * answer and of the retry got two different answers for one operation.
 *
 * <p>The chain under test is the real one: JwtAuthFilter → IdempotencyFilter
 * (transaction + advisory lock) → controller (which sets {@code Location}) →
 * the persisted record → the replayed response.
 */
@SpringBootTest(classes = TestMain.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("test")
class IdempotencyReplayHeadersIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private IdempotencyRecordRepository idempotencyRecordRepository;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private String adminToken;

    @BeforeAll
    void setup() throws Exception {
        adminToken = loginAndGetToken("admin", "admin");
    }

    @Test
    void replay_sameKey_returnsTheSameLocationHeader() throws Exception {
        String procKey = "audit14-loc-" + UUID.randomUUID().toString().substring(0, 8);
        deployProcess(procKey);
        String body = "{\"processDefinitionKey\":\"" + procKey + "\",\"variables\":[]}";
        String key = UUID.randomUUID().toString();

        MvcResult first = mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + adminToken)
                .header("Idempotency-Key", key)
                .content(body)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();

        String firstLocation = first.getResponse().getHeader("Location");
        assertThat(firstLocation)
            .as("the original answer carries the Location of the created instance")
            .isNotNull();
        assertThat(firstLocation).startsWith("/process-instances/");

        MvcResult replay = mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + adminToken)
                .header("Idempotency-Key", key)
                .content(body)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();

        assertThat(replay.getResponse().getHeader("Location"))
            .as("the replay must answer with the SAME Location — otherwise the retry " +
                "is a different answer than the original")
            .isEqualTo(firstLocation);
        assertThat(replay.getResponse().getContentAsString())
            .as("the body is the same answer too")
            .isEqualTo(first.getResponse().getContentAsString());
    }

    @Test
    void persistedRecord_carriesTheLocationHeader() throws Exception {
        String procKey = "audit14-persist-" + UUID.randomUUID().toString().substring(0, 8);
        deployProcess(procKey);
        String body = "{\"processDefinitionKey\":\"" + procKey + "\",\"variables\":[]}";
        String key = UUID.randomUUID().toString();

        MvcResult first = mockMvc.perform(post("/process-instances")
                .header("Authorization", "Bearer " + adminToken)
                .header("Idempotency-Key", key)
                .content(body)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();

        List<IdempotencyRecord> records =
            idempotencyRecordRepository.findByIdemKeyAndEndpoint(key, "/process-instances");
        assertThat(records).as("the idempotency record must exist").hasSize(1);
        IdempotencyRecord record = records.get(0);
        assertThat(record.getResponseHeaders())
            .as("the Location must be persisted in the idempotency record")
            .isNotNull()
            .contains(first.getResponse().getHeader("Location"));
    }

    // ==================== helpers (proven shapes) ====================

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

    private void deployProcess(String key) throws Exception {
        String bpmn = new String(Files.readAllBytes(Paths.get("src/test/files/process1.bpmn")));
        bpmn = bpmn.replace("id=\"process1\"", "id=\"" + key + "\"")
                   .replace("name=\"Process 1\"", "name=\"" + key + "\"")
                   .replace("process id=\"process1\"", "process id=\"" + key + "\"");
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn(bpmn);
        mockMvc.perform(post("/process-definitions")
                .header("Authorization", "Bearer " + adminToken)
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated());
    }
}
