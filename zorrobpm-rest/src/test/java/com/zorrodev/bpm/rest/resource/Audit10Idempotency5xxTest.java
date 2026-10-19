package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.service.ScriptService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-AUDIT-10 / F-08 (High): a transient 500 on an idempotent endpoint
 * must NOT be cached — the retry with the same key must re-execute.
 *
 * <p>Real stack (V11): real {@code IdempotencyFilter} in the chain, real
 * {@code GlobalExceptionHandler} producing the 500, real H2 persistence,
 * real HTTP through the RANDOM_PORT server. The fault is injected in
 * {@code ScriptService.admitOutsideTx()} — the ENG-35 admission call made
 * by {@code RuntimeResource.gatedFeel} BEFORE the transactional service
 * (a real method that fails under script-pool saturation). Pre-transaction
 * placement matters: a fault inside the {@code @Transactional} service
 * marks the transaction rollback-only and the 500 row is rolled back with
 * it (the retry re-executes by accident). The audit F-08 defect is the
 * persistence path, which is what this probe exercises.
 *
 * <p>Current behavior (RED): the 500 is persisted, the retry with the
 * same key replays the cached 500 forever. Expected after F-08: the
 * retry re-executes and returns 201 with a fresh instance.
 *
 * <p>Deterministic failures (400/413) are allowed into the cache by
 * design (retry with the same body is deterministic) — the full status
 * table is pinned by {@code Audit10IdempotencyCacheGateTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("test")
class Audit10Idempotency5xxTest {

    @MockitoSpyBean
    private ScriptService scriptService;

    @LocalServerPort
    private int port;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final HttpClient http = HttpClient.newHttpClient();

    private String adminToken;
    private String processKey;

    @BeforeAll
    void setup() throws Exception {
        adminToken = login();
        processKey = "audit10-key-" + UUID.randomUUID().toString().substring(0, 8);
        deployProcess(processKey);
        // F-08 fault: first start → transient admission failure (500 via
        // GlobalExceptionHandler), second start → real execution.
        Mockito.doThrow(new RuntimeException("simulated transient backend failure"))
            .doCallRealMethod()
            .when(scriptService).admitOutsideTx();
    }

    @Test
    void f08_transient500_notCached_retryReexecutes() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = "{\"processDefinitionKey\":\"" + processKey + "\",\"variables\":[]}";

        HttpResponse<String> first = postStart(key, body);
        assertThat(first.statusCode())
            .as("precondition: the first start fails with a 500 (transient backend fault)")
            .isEqualTo(500);

        HttpResponse<String> second = postStart(key, body);
        assertThat(second.statusCode())
            .as("F-08: retry after a transient 500 must RE-EXECUTE, not replay the cached 500")
            .isEqualTo(201);
        JsonNode created = mapper.readTree(second.body());
        assertThat(created.has("id"))
            .as("re-execution created a real instance, body=%s", second.body())
            .isTrue();
        UUID.fromString(created.get("id").asText());
    }

    // ==================== helpers ====================

    private HttpResponse<String> postStart(String idempotencyKey, String body) throws Exception {
        return http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/process-instances"))
                .header("Authorization", "Bearer " + adminToken)
                .header("Idempotency-Key", idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private String login() throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername("admin");
        dto.setPassword("admin");
        HttpResponse<String> res = http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/auth/login"))
                .header("X-Auth-Transport", "bearer")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(dto)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode())
            .as("login failed: %s", res.body())
            .isEqualTo(200);
        return mapper.readTree(res.body()).get("token").asText();
    }

    private void deployProcess(String key) throws Exception {
        String xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_audit10" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="%s" name="%s" isExecutable="true">
                <bpmn:startEvent id="startEvent" name="startEvent">
                  <bpmn:outgoing>flow1</bpmn:outgoing>
                </bpmn:startEvent>
                <bpmn:sequenceFlow id="flow1" name="flow1" sourceRef="startEvent" targetRef="endEvent" />
                <bpmn:endEvent id="endEvent" name="endEvent">
                  <bpmn:incoming>flow1</bpmn:incoming>
                </bpmn:endEvent>
              </bpmn:process>
            </bpmn:definitions>
            """.formatted(key, key);
        AddProcessDefinitionDTO dto = new AddProcessDefinitionDTO();
        dto.setBpmn(xml);
        HttpResponse<String> res = http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/process-definitions"))
                .header("Authorization", "Bearer " + adminToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(dto)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode())
            .as("deploy failed: %s", res.body())
            .isEqualTo(201);
    }
}
