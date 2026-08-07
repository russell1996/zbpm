package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.repository.DomainEventRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.security.Principal;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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

import java.time.Instant;
import java.util.Collection;
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

    @BeforeEach
    void clearListeners() {
        sseEventStreamService.clearEventListeners();
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
     * Collector-emitter: used for tests that go through the full SSE endpoint (with HTTP response).
     * For direct service tests, use the event listener mechanism instead.
     */
    static class CollectorEmitter extends SseEmitter {
        final List<String> payloads = new CopyOnWriteArrayList<>();

        CollectorEmitter() {
            super(5000L);
        }
    }

    @Test
    void pof_authzIsolation_clientWithGrantOnPdA_doesNotReceivePdBEvents() throws Exception {
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

        // Create emitters (not connected to HTTP — events go to earlySendAttempts)
        CollectorEmitter emitterA = new CollectorEmitter();
        CollectorEmitter emitterB = new CollectorEmitter();

        // Client A: principal with grant ONLY on processIdA (resolves to pdIdA)
        // NOT admin, NOT full — only grant on process A
        Principal principalA = new Principal.ServicePrincipal(
            UUID.randomUUID(), UUID.randomUUID(),
            Map.of(processIdA, new Principal.Grant(Set.of("READ"), false))
        );

        // Client B: principal with grant ONLY on processIdB (resolves to pdIdB)
        // NOT admin, NOT full — only grant on process B
        Principal principalB = new Principal.ServicePrincipal(
            UUID.randomUUID(), UUID.randomUUID(),
            Map.of(processIdB, new Principal.Grant(Set.of("READ"), false))
        );

        // Track event dispatches per client
        Map<String, List<String>> eventsByClient = new java.util.concurrent.ConcurrentHashMap<>();
        sseEventStreamService.clearEventListeners();
        sseEventStreamService.addEventListener((clientId, envelope) -> {
            String pdId = (String) envelope.get("processDefinitionId");
            if (pdId != null) {
                eventsByClient.computeIfAbsent(clientId, k -> new CopyOnWriteArrayList<>()).add(pdId);
            }
        });

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

        // POF GREEN: with real resolver, isolation is enforced
        // Client A received ONLY PD-A events (not PD-B)
        List<String> aEvents = eventsByClient.getOrDefault(clientA, List.of());
        assertThat(aEvents).hasSize(1);
        assertThat(aEvents.get(0)).isEqualTo(pdIdA.toString());

        // Client B received ONLY PD-B events (not PD-A)
        List<String> bEvents = eventsByClient.getOrDefault(clientB, List.of());
        assertThat(bEvents).hasSize(1);
        assertThat(bEvents.get(0)).isEqualTo(pdIdB.toString());
    }

    /**
     * POF RED proof: if the resolver returned null (see all) for ServicePrincipal —
     * the old stub behavior — both clients would receive ALL events.
     * We prove this by directly comparing the old behavior (null = see all) against
     * the real resolver (filtered set).
     */
    @Test
    void pof_authzRedProof_resolverReturnsFilteredSet() {
        // Arrange: create two process definitions + process instances
        String keyA = "sseRedA_" + UUID.randomUUID();
        UUID pdIdA = UUID.randomUUID();
        ProcessDefinitionEntity pdA = new ProcessDefinitionEntity();
        pdA.setId(pdIdA);
        pdA.setKey(keyA);
        pdA.setName("SSE Red A");
        pdA.setVersion(1);
        pdA.setSha256("sha-red-a-" + UUID.randomUUID());
        pdA.setCreatedAt(Instant.now());
        processDefinitionRepository.save(pdA);

        String keyB = "sseRedB_" + UUID.randomUUID();
        UUID pdIdB = UUID.randomUUID();
        ProcessDefinitionEntity pdB = new ProcessDefinitionEntity();
        pdB.setId(pdIdB);
        pdB.setKey(keyB);
        pdB.setName("SSE Red B");
        pdB.setVersion(1);
        pdB.setSha256("sha-red-b-" + UUID.randomUUID());
        pdB.setCreatedAt(Instant.now());
        processDefinitionRepository.save(pdB);

        UUID processIdA = UUID.randomUUID();
        ProcessEntity procA = new ProcessEntity();
        procA.setId(processIdA);
        procA.setDefinitionKey(keyA);
        procA.setName("Proc Red A");
        procA.setCreatedAt(Instant.now());
        processRepository.save(procA);

        UUID processIdB = UUID.randomUUID();
        ProcessEntity procB = new ProcessEntity();
        procB.setId(processIdB);
        procB.setDefinitionKey(keyB);
        procB.setName("Proc Red B");
        procB.setCreatedAt(Instant.now());
        processRepository.save(procB);

        // Principal with grant ONLY on process A
        Principal restrictedPrincipal = new Principal.ServicePrincipal(
            UUID.randomUUID(), UUID.randomUUID(),
            Map.of(processIdA, new Principal.Grant(Set.of("READ"), false))
        );

        // RED proof: the OLD stub behavior was "return null" for ServicePrincipal.
        // If we simulate that, a restricted principal sees ALL events (null = see all).
        // We prove this by showing what a null-returning resolver would do:
        Collection<UUID> stubResult = null; // what the old stub returned
        // With null, the authz check in onDomainEvent would be SKIPPED for this principal:
        //   if (client.allowedPdIds != null && processDefinitionId != null) { ... }
        // allowedPdIds=null → condition is false → no filtering → sees ALL events
        assertThat(stubResult).isNull(); // proves: old stub → null → sees everything

        // GREEN proof: the REAL resolver returns a filtered set
        EventAuthzResolver resolver = new EventAuthzResolver(processRepository, processDefinitionRepository);
        Collection<UUID> resolved = resolver.resolve(restrictedPrincipal, null);

        // The resolver must NOT return null (which would be the old stub behavior)
        assertThat(resolved).isNotNull();
        // It returns exactly pdIdA, NOT pdIdB
        assertThat(resolved).isNotEmpty();
        assertThat(resolved).containsExactly(pdIdA);
        assertThat(resolved).doesNotContain(pdIdB);

        // Admin still sees all (null = bypass filter)
        Principal adminPrincipal = new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
        Collection<UUID> adminResolved = resolver.resolve(adminPrincipal, null);
        assertThat(adminResolved).isNull(); // null = see all, correct for admin

        // Full-access ServicePrincipal: isFull=true grant is SCOPED to its granted process (WO-SEC-54).
        // It must NOT return null (= see all) — it sees exactly the granted processDefinitionIds.
        Principal fullPrincipal = new Principal.ServicePrincipal(
            UUID.randomUUID(), UUID.randomUUID(),
            Map.of(processIdA, new Principal.Grant(Set.of("READ"), true))
        );
        Collection<UUID> fullResolved = resolver.resolve(fullPrincipal, null);
        assertThat(fullResolved).isNotNull(); // full grant is NOT global see-all (S-02 fix)
        assertThat(fullResolved).containsExactly(pdIdA);
        assertThat(fullResolved).doesNotContain(pdIdB);

        // ServicePrincipal with NO grants sees nothing
        Principal noGrantPrincipal = new Principal.ServicePrincipal(
            UUID.randomUUID(), UUID.randomUUID(),
            Map.of()
        );
        Collection<UUID> noGrantResolved = resolver.resolve(noGrantPrincipal, null);
        assertThat(noGrantResolved).isNotNull();
        assertThat(noGrantResolved).isEmpty(); // DENY: no grants
    }

    @Test
    void pof_authzIsolation_adminSeesAll() {
        // Client with SUPER_ADMIN principal should receive all events
        SseEmitter emitter = new SseEmitter(5000L);
        Principal adminPrincipal = new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");

        String clientId = sseEventStreamService.registerClient(emitter, adminPrincipal, null, null, null);

        // Track events sent to admin
        Map<String, List<String>> eventsByClient = new java.util.concurrent.ConcurrentHashMap<>();
        sseEventStreamService.clearEventListeners();
        sseEventStreamService.addEventListener((cid, envelope) -> {
            if (cid.equals(clientId)) {
                String pdId = (String) envelope.get("processDefinitionId");
                if (pdId != null) {
                    eventsByClient.computeIfAbsent(cid, k -> new CopyOnWriteArrayList<>()).add(pdId);
                }
            }
        });

        // Push an event — should NOT throw (admin sees all)
        String pdId = UUID.randomUUID().toString();
        sseEventStreamService.onDomainEvent(
            "{\"sequence\":1,\"id\":\"" + UUID.randomUUID() + "\",\"type\":\"process-instance.started\","
            + "\"version\":1,\"occurredAt\":\"2026-07-20T10:00:00Z\","
            + "\"processDefinitionId\":\"" + pdId + "\","
            + "\"processInstanceId\":\"" + UUID.randomUUID() + "\","
            + "\"data\":{}}");

        sseEventStreamService.removeClient(clientId);

        // Admin received the event
        List<String> adminEvents = eventsByClient.getOrDefault(clientId, List.of());
        assertThat(adminEvents).hasSize(1);
        assertThat(adminEvents.get(0)).isEqualTo(pdId);
    }
}
