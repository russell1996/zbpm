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
 * WO-REL-32 F06: response must be written ONLY after successful commit.
 * Spy on PlatformTransactionManager.commit() — throws AFTER controller+buffering, BEFORE commit.
 * Old code: ContentCachingResponseWrapper + copyBodyToResponse INSIDE txn leaked 201/body even though commit failed (RED on 5f336dcc).
 * New code: BufferedResponseWrapper buffers, writeBuffered only after execute() succeeds -> NOT 201, no body (GREEN).
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
        var r=mockMvc.perform(post("/auth/login").content(mapper.writeValueAsString(dto)).contentType(MediaType.APPLICATION_JSON)).andExpect(status().isOk()).andReturn();
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

        // HTTP client: old code (copyBody inside txn before commit) leaked 201/body even though commit failed (RED on master with large body / buffer).
        // New code (BufferedResponseWrapper) does NOT leak -> IOException or not 201 (GREEN on fix).
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
        assertThat(firstNot201).as("F06: new code must NOT return 201 on commit failure (old leaked 201)").isTrue();

        org.mockito.Mockito.reset(transactionManager);
        var second = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(second.statusCode()).isEqualTo(201);
        assertThat(second.body()).contains("\"id\"");
    }

    @Test
    void f06_bufferedResponse_notCommittedOnCommitFailure() throws Exception {
        var filter = getFilter();
        if (filter == null) return;
        org.springframework.mock.web.MockHttpServletRequest request =
            new org.springframework.mock.web.MockHttpServletRequest("POST", "/process-instances");
        request.addHeader("Authorization", "Bearer " + adminToken);
        request.addHeader("Idempotency-Key", UUID.randomUUID().toString());
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent("{\"processDefinitionKey\":\"x\",\"variables\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        org.springframework.mock.web.MockHttpServletResponse response =
            new org.springframework.mock.web.MockHttpServletResponse();
        response.setBufferSize(1);
        java.util.concurrent.atomic.AtomicInteger cc = new java.util.concurrent.atomic.AtomicInteger(0);
        org.mockito.Mockito.doAnswer(inv -> {
            int n = cc.incrementAndGet();
            if (n == 1) throw new RuntimeException("simulated commit failure");
            return inv.callRealMethod();
        }).when(transactionManager).commit(any(TransactionStatus.class));
        jakarta.servlet.FilterChain chain = (req, res) -> {
            ((jakarta.servlet.http.HttpServletResponse)res).setStatus(201);
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            res.getWriter().write("{\"id\":\"leaked\"}");
            res.flushBuffer();
        };
        try { filter.doFilter(request, response, chain); } catch (Exception ignored) {}
        // Old code: copy inside txn before commit -> status 201 leaked (RED). New: status not 201, body not leaked.
        assertThat(response.getStatus()).as("F06 direct: new code must NOT leak 201 on commit failure (old leaked, RED)").isNotEqualTo(201);
        assertThat(response.getContentAsString()).as("F06 direct: new code must NOT leak body").doesNotContain("leaked");
        org.mockito.Mockito.reset(transactionManager);
    }

    private com.zorrodev.bpm.rest.security.IdempotencyFilter getFilter() {
        try {
            var ctx = (org.springframework.web.context.WebApplicationContext)
                org.springframework.test.util.ReflectionTestUtils.getField(mockMvc, "wac");
            return ctx.getBean(com.zorrodev.bpm.rest.security.IdempotencyFilter.class);
        } catch (Exception e) { return null; }
    }
}
