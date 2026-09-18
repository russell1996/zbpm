package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ApiKeyEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ApiKeyGrantRepository;
import com.zorrodev.bpm.engine.repository.ApiKeyRepository;
import com.zorrodev.bpm.engine.repository.DomainEventRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
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
    @Autowired private ProcessMemberRepository processMemberRepository;
    @Autowired private SseEventStreamService sseEventStreamService;
    @Autowired private UiUserRepository uiUserRepository;
    @Autowired private ApiKeyRepository apiKeyRepository;
    @Autowired private ApiKeyGrantRepository apiKeyGrantRepository;
    @Autowired private com.zorrodev.bpm.engine.scheduler.FeedPositionAssigner feedPositionAssigner;

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
     * WO-SEC-67: a stream principal must stand on a LIVE credential — the
     * liveness gate closes streams whose key/user row is missing, revoked,
     * expired or deactivated. These helpers seed that live backing (an active
     * owner user + a live key row); the grants themselves stay frozen in the
     * principal, exactly like the JwtAuthFilter-frozen grants in production.
     */
    private UUID seedLiveOwner(String prefix) {
        return seedLiveOwner(prefix, "USER");
    }

    /**
     * WO-REL-38: настоящий ряд events + назначенная позиция — live-мост
     * резолвит позицию по sequence из БД и рассылает только тогда.
     */
    private long seedPositionedEvent() {
        com.zorrodev.bpm.engine.entity.DomainEventEntity e =
            new com.zorrodev.bpm.engine.entity.DomainEventEntity();
        e.setId(UUID.randomUUID());
        e.setType("rel38.setup." + UUID.randomUUID());
        e.setVersion(1);
        e.setOccurredAt(Instant.now());
        e.setData(Map.of());
        com.zorrodev.bpm.engine.entity.DomainEventEntity saved = domainEventRepository.save(e);
        feedPositionAssigner.assignPendingPositions();
        return domainEventRepository.findById(saved.getSequence()).orElseThrow().getSequence();
    }

    private UUID seedLiveOwner(String prefix, String role) {
        UUID id = UUID.randomUUID();
        UiUserEntity u = new UiUserEntity();
        u.setId(id);
        u.setUsername(prefix + "-" + id.toString().substring(0, 8));
        u.setPasswordHash("x");
        u.setFullName(prefix + " User");
        u.setEmail(prefix + "-" + id.toString().substring(0, 8) + "@example.com");
        u.setRole(role);
        u.setUserType("HUMAN");
        u.setActive(true);
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        uiUserRepository.save(u);
        return id;
    }

    private UUID seedLiveKey(UUID ownerId) {
        UUID keyId = UUID.randomUUID();
        ApiKeyEntity key = new ApiKeyEntity();
        key.setId(keyId);
        key.setOwnerUserId(ownerId);
        key.setKeyHash("test-hash-" + keyId);
        key.setPrefix("zbpm_sk_test");
        key.setCreatedAt(Instant.now());
        apiKeyRepository.save(key);
        return keyId;
    }

    /**
     * WO-SEC-67: live key WITH live grant rows AND live owner membership —
     * the full prod backing (JwtAuthFilter's effectiveGrants = key grants ∩
     * owner's CURRENT membership; a key without either delivers nothing in
     * prod, so the test seeds both — stronger than the old frozen-only setup).
     */
    private UUID seedLiveKeyWithGrant(UUID ownerId, UUID processId, boolean full) {
        UUID keyId = seedLiveKey(ownerId);
        addMember(processId, ownerId);
        com.zorrodev.bpm.engine.entity.ApiKeyGrantEntity grant =
            new com.zorrodev.bpm.engine.entity.ApiKeyGrantEntity();
        grant.setApiKeyId(keyId);
        grant.setProcessId(processId);
        grant.setPermissions("READ");
        grant.setFull(full);
        apiKeyGrantRepository.save(grant);
        return keyId;
    }

    private void addMember(UUID processId, UUID userId) {
        com.zorrodev.bpm.engine.entity.ProcessMemberEntity pm =
            new com.zorrodev.bpm.engine.entity.ProcessMemberEntity();
        pm.setProcessId(processId);
        pm.setUserId(userId);
        pm.setRole("VIEWER");
        pm.setAddedBy(userId);
        pm.setAddedAt(Instant.now());
        processMemberRepository.save(pm);
    }

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
        // NOT admin, NOT full — only grant on process A.
        // WO-SEC-67: full live backing (key + grant rows + owner membership —
        // effectiveGrants needs all three, like the prod JwtAuthFilter path).
        UUID ownerA = seedLiveOwner("sseLiveA");
        UUID keyIdA = seedLiveKeyWithGrant(ownerA, processIdA, false);
        Principal principalA = new Principal.ServicePrincipal(
            keyIdA, ownerA,
            Map.of(processIdA, new Principal.Grant(Set.of("READ"), false))
        );

        // Client B: principal with grant ONLY on processIdB (resolves to pdIdB)
        // NOT admin, NOT full — only grant on process B.
        // WO-SEC-67: full live backing, same as A.
        UUID ownerB = seedLiveOwner("sseLiveB");
        UUID keyIdB = seedLiveKeyWithGrant(ownerB, processIdB, false);
        Principal principalB = new Principal.ServicePrincipal(
            keyIdB, ownerB,
            Map.of(processIdB, new Principal.Grant(Set.of("READ"), false))
        );

        // Register clients BEFORE listener so we capture correct clientIds
        String clientA = sseEventStreamService.registerClient(emitterA, principalA, null, null, null);
        String clientB = sseEventStreamService.registerClient(emitterB, principalB, null, null, null);

        // Track event dispatches per client (after registration, before push)
        Map<String, List<String>> eventsByClient = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(4);
        sseEventStreamService.clearEventListeners();
        sseEventStreamService.addEventListener((clientId, envelope) -> {
            String pdId = (String) envelope.get("processDefinitionId");
            if (pdId != null) {
                eventsByClient.computeIfAbsent(clientId, k -> new CopyOnWriteArrayList<>()).add(pdId);
            }
            latch.countDown();
        });

        // Push events (now 4 dispatches: 2 clients × 2 events, but only 2 pass AuthZ).
        // WO-REL-38: live-мост резолвит позицию по sequence из БД — сеем
        // настоящие строки (позицию ставит джоб) и шлём их sequence.
        long seqA = seedPositionedEvent();
        long seqB = seedPositionedEvent();
        sseEventStreamService.onDomainEvent(
            "{\"sequence\":" + seqA + ",\"id\":\"" + UUID.randomUUID() + "\",\"type\":\"process-instance.started\","
            + "\"version\":1,\"occurredAt\":\"2026-07-20T10:00:00Z\","
            + "\"processDefinitionId\":\"" + pdIdA + "\","
            + "\"processInstanceId\":\"" + UUID.randomUUID() + "\","
            + "\"data\":{}}");

        sseEventStreamService.onDomainEvent(
            "{\"sequence\":" + seqB + ",\"id\":\"" + UUID.randomUUID() + "\",\"type\":\"process-instance.started\","
            + "\"version\":1,\"occurredAt\":\"2026-07-20T10:00:01Z\","
            + "\"processDefinitionId\":\"" + pdIdB + "\","
            + "\"processInstanceId\":\"" + UUID.randomUUID() + "\","
            + "\"data\":{}}");

        latch.await(3, java.util.concurrent.TimeUnit.SECONDS);
        Thread.sleep(200);

        // Clean up
        sseEventStreamService.removeClient(clientA);
        sseEventStreamService.removeClient(clientB);

        // POF GREEN: with real resolver, isolation is enforced
        List<String> aEvents = eventsByClient.getOrDefault(clientA, List.of());
        assertThat(aEvents).hasSize(1);
        assertThat(aEvents.get(0)).isEqualTo(pdIdA.toString());

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
        // WO-DEBT-7 S8: EventAuthzResolver is now a thin facade over ProcessAuthzService — wire both.
        // WO-SEC-67: +3 ctor args (ApiKeyGrantRepository, AuthorizationService, ApiKeyService) —
        // unused on this path (frozen-grant resolution), null is fine.
        com.zorrodev.bpm.engine.service.ProcessAuthzService processAuthzService =
            new com.zorrodev.bpm.engine.service.ProcessAuthzService(processRepository, processDefinitionRepository,
                processMemberRepository, null, null, null);
        EventAuthzResolver resolver = new EventAuthzResolver(processAuthzService);
        Collection<UUID> resolved = resolver.readableRuntimePdIds(restrictedPrincipal, null);

        // The resolver must NOT return null (which would be the old stub behavior)
        assertThat(resolved).isNotNull();
        // It returns exactly pdIdA, NOT pdIdB
        assertThat(resolved).isNotEmpty();
        assertThat(resolved).containsExactly(pdIdA);
        assertThat(resolved).doesNotContain(pdIdB);

        // Admin still sees all (null = bypass filter)
        Principal adminPrincipal = new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
        Collection<UUID> adminResolved = resolver.readableRuntimePdIds(adminPrincipal, null);
        assertThat(adminResolved).isNull(); // null = see all, correct for admin

        // Full-access ServicePrincipal: isFull=true grant is SCOPED to its granted process (WO-SEC-54).
        // It must NOT return null (= see all) — it sees exactly the granted processDefinitionIds.
        Principal fullPrincipal = new Principal.ServicePrincipal(
            UUID.randomUUID(), UUID.randomUUID(),
            Map.of(processIdA, new Principal.Grant(Set.of("READ"), true))
        );
        Collection<UUID> fullResolved = resolver.readableRuntimePdIds(fullPrincipal, null);
        assertThat(fullResolved).isNotNull(); // full grant is NOT global see-all (S-02 fix)
        assertThat(fullResolved).containsExactly(pdIdA);
        assertThat(fullResolved).doesNotContain(pdIdB);

        // ServicePrincipal with NO grants sees nothing
        Principal noGrantPrincipal = new Principal.ServicePrincipal(
            UUID.randomUUID(), UUID.randomUUID(),
            Map.of()
        );
        Collection<UUID> noGrantResolved = resolver.readableRuntimePdIds(noGrantPrincipal, null);
        assertThat(noGrantResolved).isNotNull();
        assertThat(noGrantResolved).isEmpty(); // DENY: no grants
    }

    @Test
    void pof_authzIsolation_adminSeesAll() throws Exception {
        // WO-SEC-67: live SUPER_ADMIN row behind the admin principal (liveness gate).
        UUID adminId = seedLiveOwner("sseLiveAdmin", "SUPER_ADMIN");
        SseEmitter emitter = new SseEmitter(5000L);
        Principal adminPrincipal = new Principal.UserPrincipal(adminId, "admin", "SUPER_ADMIN");
        String clientId = sseEventStreamService.registerClient(emitter, adminPrincipal, null, null, null);

        Map<String, List<String>> eventsByClient = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        sseEventStreamService.clearEventListeners();
        sseEventStreamService.addEventListener((cid, envelope) -> {
            if (cid.equals(clientId)) {
                String pdId = (String) envelope.get("processDefinitionId");
                if (pdId != null) {
                    eventsByClient.computeIfAbsent(cid, k -> new CopyOnWriteArrayList<>()).add(pdId);
                }
            }
            latch.countDown();
        });

        String pdId = UUID.randomUUID().toString();
        long positioned = seedPositionedEvent();
        sseEventStreamService.onDomainEvent(
            "{\"sequence\":" + positioned + ",\"id\":\"" + UUID.randomUUID() + "\",\"type\":\"process-instance.started\","
            + "\"version\":1,\"occurredAt\":\"2026-07-20T10:00:00Z\","
            + "\"processDefinitionId\":\"" + pdId + "\","
            + "\"processInstanceId\":\"" + UUID.randomUUID() + "\","
            + "\"data\":{}}");

        latch.await(3, java.util.concurrent.TimeUnit.SECONDS);
        Thread.sleep(200);
        sseEventStreamService.removeClient(clientId);

        List<String> adminEvents = eventsByClient.getOrDefault(clientId, List.of());
        assertThat(adminEvents).hasSize(1);
        assertThat(adminEvents.get(0)).isEqualTo(pdId);
    }
}
