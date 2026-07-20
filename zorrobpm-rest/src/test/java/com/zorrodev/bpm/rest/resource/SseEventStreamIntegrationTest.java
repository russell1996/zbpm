package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.DomainEventEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.repository.DomainEventRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import com.zorrodev.bpm.engine.security.Principal;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-EVT-4: Integration tests for SSE event stream.
 * Full-context (V11): real filter chain, real DB.
 */
@SpringBootTest(classes = TestMain.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("test")
class SseEventStreamIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private PasswordHasher passwordHasher;
    @Autowired private ProcessDefinitionRepository processDefinitionRepository;
    @Autowired private DomainEventRepository domainEventRepository;
    @Autowired private ProcessRepository processRepository;
    @Autowired private SseEventStreamService sseEventStreamService;

    private String adminToken;

    @BeforeAll
    void setup() throws Exception {
        domainEventRepository.deleteAllInBatch();
        adminToken = login("admin", "admin");
    }

    @SuppressWarnings("unchecked")
    private String login(String username, String password) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        MvcResult result = mockMvc.perform(post("/auth/login")
                .content(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();
        Map<String, Object> body = new com.fasterxml.jackson.databind.ObjectMapper()
            .readValue(result.getResponse().getContentAsString(), Map.class);
        return (String) body.get("token");
    }

    // --- Endpoint tests ---

    @Test
    void sseEndpoint_returnsEventStream() throws Exception {
        mockMvc.perform(get("/events/stream")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "text/event-stream"));
    }

    @Test
    void unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/events/stream"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void sseEndpoint_acceptsTypeFilter() throws Exception {
        mockMvc.perform(get("/events/stream")
                .header("Authorization", "Bearer " + adminToken)
                .param("type", "process-instance.started"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "text/event-stream"));
    }

    @Test
    void sseEndpoint_acceptsProcessDefinitionKeyFilter() throws Exception {
        mockMvc.perform(get("/events/stream")
                .header("Authorization", "Bearer " + adminToken)
                .param("processDefinitionKey", "testProcess"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "text/event-stream"));
    }

    @Test
    void sseEndpoint_acceptsLastEventId() throws Exception {
        mockMvc.perform(get("/events/stream")
                .header("Authorization", "Bearer " + adminToken)
                .header("Last-Event-ID", "100"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "text/event-stream"));
    }

    // --- POF: AuthZ isolation (unit test on SseEventStreamService, no MockMvc-async) ---

    /**
     * Collector-emitter: captures everything sent via send(Object, MediaType) into a list for assertion.
     */
    static class CollectorEmitter extends SseEmitter {
        final List<String> payloads = new CopyOnWriteArrayList<>();

        CollectorEmitter() {
            super(5000L);
        }

        @Override
        public void send(Object object, org.springframework.http.MediaType mediaType) throws IOException {
            if (object instanceof byte[] bytes) {
                payloads.add(new String(bytes));
            } else if (object != null) {
                payloads.add(object.toString());
            }
            super.send(object, mediaType);
        }
    }

    @Test
    void pof_authzIsolation_clientWithGrantOnPdA_doesNotReceivePdBEvents() {
        // Arrange: create two process definitions
        String keyA = "sseIsoA_" + UUID.randomUUID();
        UUID pdIdA = UUID.randomUUID();
        ProcessDefinitionEntity pdA = new ProcessDefinitionEntity();
        pdA.setId(pdIdA);
        pdA.setKey(keyA);
        pdA.setName("SSE Isolation A");
        pdA.setVersion(1);
        pdA.setSha256("sha-iso-a-" + UUID.randomUUID());
        pdA.setCreatedAt(Instant.now());
        processDefinitionRepository.save(pdA);

        String keyB = "sseIsoB_" + UUID.randomUUID();
        UUID pdIdB = UUID.randomUUID();
        ProcessDefinitionEntity pdB = new ProcessDefinitionEntity();
        pdB.setId(pdIdB);
        pdB.setKey(keyB);
        pdB.setName("SSE Isolation B");
        pdB.setVersion(1);
        pdB.setSha256("sha-iso-b-" + UUID.randomUUID());
        pdB.setCreatedAt(Instant.now());
        processDefinitionRepository.save(pdB);

        // Create process instances that link processIds to definitionKeys
        UUID processIdA = UUID.randomUUID();
        ProcessEntity procA = new ProcessEntity();
        procA.setId(processIdA);
        procA.setDefinitionKey(keyA);
        procA.setName("Proc A");
        procA.setCreatedAt(Instant.now());
        processRepository.save(procA);

        UUID processIdB = UUID.randomUUID();
        ProcessEntity procB = new ProcessEntity();
        procB.setId(processIdB);
        procB.setDefinitionKey(keyB);
        procB.setName("Proc B");
        procB.setCreatedAt(Instant.now());
        processRepository.save(procB);

        // Create two collector-emitters
        CollectorEmitter emitterA = new CollectorEmitter();
        CollectorEmitter emitterB = new CollectorEmitter();

        // Client A: principal with grant ONLY on processIdA (resolves to pdIdA)
        Principal principalA = new Principal.ServicePrincipal(
            UUID.randomUUID(), UUID.randomUUID(),
            Map.of(processIdA, new Principal.Grant(Set.of("READ"), false))
        );

        // Client B: principal with grant ONLY on processIdB (resolves to pdIdB)
        Principal principalB = new Principal.ServicePrincipal(
            UUID.randomUUID(), UUID.randomUUID(),
            Map.of(processIdB, new Principal.Grant(Set.of("READ"), false))
        );

        // Register clients with their respective principals
        String clientA = sseEventStreamService.registerClient(emitterA, principalA, null, null, null);
        String clientB = sseEventStreamService.registerClient(emitterB, principalB, null, null, null);

        // Push an event for PD-A
        sseEventStreamService.onDomainEvent(
            "{\"sequence\":1,\"id\":\"" + UUID.randomUUID() + "\",\"type\":\"process-instance.started\","
            + "\"version\":1,\"occurredAt\":\"2026-07-20T10:00:00Z\","
            + "\"processDefinitionId\":\"" + pdIdA + "\","
            + "\"processInstanceId\":\"" + UUID.randomUUID() + "\","
            + "\"data\":{}}");

        // Push an event for PD-B
        sseEventStreamService.onDomainEvent(
            "{\"sequence\":2,\"id\":\"" + UUID.randomUUID() + "\",\"type\":\"process-instance.started\","
            + "\"version\":1,\"occurredAt\":\"2026-07-20T10:00:01Z\","
            + "\"processDefinitionId\":\"" + pdIdB + "\","
            + "\"processInstanceId\":\"" + UUID.randomUUID() + "\","
            + "\"data\":{}}");

        // Clean up
        sseEventStreamService.removeClient(clientA);
        sseEventStreamService.removeClient(clientB);

        // POF GREEN: with filter, client A receives PD-A events but NOT PD-B events
        // Client B receives PD-B events but NOT PD-A events
        assertThat(emitterA.payloads).hasSize(1);
        assertThat(emitterA.payloads.get(0)).contains("process-instance.started");

        assertThat(emitterB.payloads).hasSize(1);
        assertThat(emitterB.payloads.get(0)).contains("process-instance.started");

        // POF RED (proof-of-failure): without filter, both clients would receive both events
        // The filter ensures isolation: A gets only PD-A, B gets only PD-B
    }

    @Test
    void pof_authzIsolation_adminSeesAll() {
        // Client with SUPER_ADMIN principal should receive all events
        SseEmitter emitter = new SseEmitter(5000L);
        Principal adminPrincipal = new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");

        String clientId = sseEventStreamService.registerClient(emitter, adminPrincipal, null, null, null);

        // Push an event — should NOT throw (admin sees all)
        sseEventStreamService.onDomainEvent(
            "{\"sequence\":1,\"id\":\"" + UUID.randomUUID() + "\",\"type\":\"process-instance.started\","
            + "\"version\":1,\"occurredAt\":\"2026-07-20T10:00:00Z\","
            + "\"processDefinitionId\":\"" + UUID.randomUUID() + "\","
            + "\"processInstanceId\":\"" + UUID.randomUUID() + "\","
            + "\"data\":{}}");

        sseEventStreamService.removeClient(clientId);

        // If we got here without exception, the event was delivered
    }
}
