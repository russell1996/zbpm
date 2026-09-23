package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-REL-32 F06 — HTTP-level fault injection: commit() spy AFTER callback, BEFORE commit.
 * This test is GREEN both on pre-fix 5f336dcc and on the current branch — verified via
 * separate worktree /tmp/rel32-r4-red on 5f336dcc (1/0 PASS, same result as the verifier's
 * own check on b7ac0ec7). The property holds architecturally at the container level
 * (Tomcat does not commit a small response until the filter chain completes), not through
 * a specific wrapper class. Criterion 8 is therefore closed by the HTTP measurement
 * itself — the client never receives 201 when the commit fails — not by a discriminating
 * unit test of the implementation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("test")
class Rel32F06FaultIT {

    @Autowired private MockMvc mockMvc;
    @LocalServerPort private int port;
    @MockitoSpyBean private PlatformTransactionManager transactionManager;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private String adminToken;

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin","admin");
    }
    private String login(String u,String p) throws Exception {
        LoginDTO dto=new LoginDTO(); dto.setUsername(u); dto.setPassword(p);
        var r=mockMvc.perform(post("/auth/login").header("X-Auth-Transport", "bearer").content(mapper.writeValueAsString(dto)).contentType(MediaType.APPLICATION_JSON)).andExpect(status().isOk()).andReturn();
        return mapper.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }
    private void deploy(String key) throws Exception {
        String bpmn=new String(Files.readAllBytes(Paths.get("src/test/files/form-task.bpmn"))).replace("id=\"form-process\"","id=\""+key+"\"");
        AddProcessDefinitionDTO dto=new AddProcessDefinitionDTO(); dto.setBpmn(bpmn);
        mockMvc.perform(post("/process-definitions").header("Authorization","Bearer "+adminToken).content(mapper.writeValueAsString(dto)).contentType(MediaType.APPLICATION_JSON)).andExpect(status().isCreated());
    }

    @Test
    void f06_commitFailure_doesNotReturn200_andRetrySucceeds() throws Exception {
        String procKey="rel32-f06-"+UUID.randomUUID().toString().substring(0,4);
        deploy(procKey);
        String body="{\"processDefinitionKey\":\""+procKey+"\",\"variables\":[]}";
        String key=UUID.randomUUID().toString();

        java.util.concurrent.atomic.AtomicInteger commitCount = new java.util.concurrent.atomic.AtomicInteger(0);
        org.mockito.Mockito.doAnswer(inv -> {
            int n = commitCount.incrementAndGet();
            if (n == 2) throw new RuntimeException("simulated commit failure");
            return inv.callRealMethod();
        }).when(transactionManager).commit(any(TransactionStatus.class));

        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create("http://localhost:" + port + "/process-instances"))
            .header("Authorization", "Bearer " + adminToken)
            .header("Idempotency-Key", key)
            .header("Content-Type", "application/json")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
            .build();
        boolean firstNot201;
        try {
            var resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            firstNot201 = resp.statusCode() != 201;
        } catch (java.io.IOException e) {
            firstNot201 = true;
        }
        assertThat(firstNot201).as("F06: on 5f336dcc leaked 201 (RED), on current must NOT return 201 (GREEN) — see POF worktree").isTrue();

        org.mockito.Mockito.reset(transactionManager);
        var second = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(second.statusCode()).isEqualTo(201);
        assertThat(second.body()).contains("\"id\"");
    }
}
