package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberId;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import com.zorrodev.bpm.engine.security.Principal;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WO-SEC-67 (F13): an open SSE stream must not survive the revocation of the
 * rights behind it.
 *
 * <p>Full-context (V11): the real {@link SseEventStreamService} bean (real
 * {@link EventAuthzResolver}, real DB), events through the real
 * {@link SseEventStreamService#onDomainEvent} dispatch path (not a manual
 * onmessage call), revocation through the real membership row delete.
 *
 * <p>Both sides (G-K): the member principal RECEIVES the pre-revoke event AND
 * does NOT receive the post-revoke event; the event-listener collector is
 * wired to the dispatch. A second, still-member principal keeps receiving —
 * the close is scoped to the revoked subject, not global.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SseRevocationIT {

    @Autowired MockMvc mockMvc;
    @Autowired SseEventStreamService sseEventStreamService;
    @Autowired UiUserRepository userRepository;
    @Autowired PasswordHasher passwordHasher;
    @Autowired ProcessRepository processRepository;
    @Autowired ProcessDefinitionRepository processDefinitionRepository;
    @Autowired ProcessMemberRepository processMemberRepository;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private static final String PASS = "SseRevoke1!secure";

    @BeforeEach
    void clearListeners() {
        sseEventStreamService.clearEventListeners();
    }

    private UUID createUser(String prefix) {
        UUID id = UUID.randomUUID();
        UiUserEntity u = new UiUserEntity();
        u.setId(id);
        u.setUsername(prefix + "-" + id.toString().substring(0, 8));
        u.setPasswordHash(passwordHasher.hash(PASS));
        u.setFullName(prefix + " User");
        u.setEmail(prefix + "-" + id.toString().substring(0, 8) + "@example.com");
        u.setRole("USER");
        u.setUserType("HUMAN");
        u.setActive(true);
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        userRepository.save(u);
        return id;
    }

    private void addMember(UUID processId, UUID userId, String role) {
        ProcessMemberEntity pm = new ProcessMemberEntity();
        pm.setProcessId(processId);
        pm.setUserId(userId);
        pm.setRole(role);
        pm.setAddedBy(userId);
        pm.setAddedAt(Instant.now());
        processMemberRepository.save(pm);
    }

    /** Process + definition sharing one definitionKey (the membership→pdId join path). */
    private UUID seedProcessWithDefinition(String keyPrefix) {
        String key = keyPrefix + "_" + UUID.randomUUID();
        UUID processId = UUID.randomUUID();
        ProcessEntity proc = new ProcessEntity();
        proc.setId(processId);
        proc.setDefinitionKey(key);
        proc.setName("SSE revoke proc");
        proc.setCreatedAt(Instant.now());
        processRepository.save(proc);

        ProcessDefinitionEntity pd = new ProcessDefinitionEntity();
        pd.setId(UUID.randomUUID());
        pd.setKey(key);
        pd.setName("SSE revoke def");
        pd.setVersion(1);
        pd.setSha256("sha-revoke-" + UUID.randomUUID());
        pd.setCreatedAt(Instant.now());
        processDefinitionRepository.save(pd);
        return processId;
    }

    private String pdIdOf(UUID processId) {
        ProcessEntity proc = processRepository.findById(processId).orElseThrow();
        return processDefinitionRepository.findAll().stream()
            .filter(d -> proc.getDefinitionKey().equals(d.getKey()))
            .findFirst().orElseThrow().getId().toString();
    }

    private void pushEvent(long sequence, String pdId) {
        sseEventStreamService.onDomainEvent(
            "{\"sequence\":" + sequence + ",\"id\":\"" + UUID.randomUUID() + "\","
            + "\"type\":\"process-instance.started\","
            + "\"version\":1,\"occurredAt\":\"2026-09-16T10:00:00Z\","
            + "\"processDefinitionId\":\"" + pdId + "\","
            + "\"processInstanceId\":\"" + UUID.randomUUID() + "\","
            + "\"data\":{}}");
    }

    @Test
    void revokedMember_liveStreamStopsReceivingWhileSurvivorKeepsGoing() throws Exception {
        // Arrange: one process, two members.
        UUID processId = seedProcessWithDefinition("sseRevoke");
        String pdId = pdIdOf(processId);
        UUID revokedUser = createUser("sse-revoked");
        UUID survivorUser = createUser("sse-survivor");
        addMember(processId, revokedUser, "OPERATOR");
        addMember(processId, survivorUser, "OPERATOR");

        Principal revokedPrincipal = new Principal.UserPrincipal(revokedUser, "revoked", "USER");
        Principal survivorPrincipal = new Principal.UserPrincipal(survivorUser, "survivor", "USER");

        SseEmitter revokedEmitter = new SseEmitter(30 * 60 * 1000L);
        SseEmitter survivorEmitter = new SseEmitter(30 * 60 * 1000L);
        String revokedClient = sseEventStreamService.registerClient(
            revokedEmitter, revokedPrincipal, null, null, null);
        String survivorClient = sseEventStreamService.registerClient(
            survivorEmitter, survivorPrincipal, null, null, null);

        try {
            Map<String, List<String>> byClient = new ConcurrentHashMap<>();
            CountDownLatch bothDelivered = new CountDownLatch(2);
            sseEventStreamService.addEventListener((clientId, envelope) -> {
                String got = (String) envelope.get("processDefinitionId");
                if (got != null) {
                    byClient.computeIfAbsent(clientId, k -> new CopyOnWriteArrayList<>()).add(got);
                    bothDelivered.countDown();
                }
            });

            // Act 1: pre-revoke event through the real dispatch — BOTH receive.
            pushEvent(1, pdId);
            // Fan-out is async (sseExecutor) — wait, never assert immediately (P-10).
            assertThat(bothDelivered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(byClient.getOrDefault(revokedClient, List.of()))
                .as("revoked principal RECEIVES the pre-revoke event").containsExactly(pdId);
            assertThat(byClient.getOrDefault(survivorClient, List.of()))
                .as("survivor RECEIVES the pre-revoke event").containsExactly(pdId);

            // Act 2: revoke membership (the real row delete — operator gone).
            processMemberRepository.deleteById(new ProcessMemberId(processId, revokedUser));
            // Event-driven invalidation, as the MemberResource hook does.
            sseEventStreamService.invalidateStreams();

            // Act 3: post-revoke event through the real dispatch.
            pushEvent(2, pdId);
            // Async fan-out: give the dispatch a moment, then assert.
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline
                && byClient.getOrDefault(survivorClient, List.of()).size() < 2) {
                Thread.sleep(50);
            }

            // Assert: revoked stream got NOTHING new; survivor got the event.
            assertThat(byClient.getOrDefault(revokedClient, List.of()))
                .as("revoked stream must NOT receive the post-revoke event")
                .containsExactly(pdId);
            assertThat(byClient.getOrDefault(survivorClient, List.of()))
                .as("survivor keeps receiving after another member's revoke")
                .containsExactly(pdId, pdId);
        } finally {
            sseEventStreamService.removeClient(revokedClient);
            sseEventStreamService.removeClient(survivorClient);
        }
    }

    @Test
    void perEventReevaluation_closesStreamEvenWithoutExplicitInvalidation() throws Exception {
        // Arrange: one member, one stream.
        UUID processId = seedProcessWithDefinition("sseReeval");
        String pdId = pdIdOf(processId);
        UUID user = createUser("sse-reeval");
        addMember(processId, user, "OPERATOR");
        Principal principal = new Principal.UserPrincipal(user, "reeval", "USER");

        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        String clientId = sseEventStreamService.registerClient(emitter, principal, null, null, null);
        try {
            Map<String, List<String>> byClient = new ConcurrentHashMap<>();
            CountDownLatch firstDelivered = new CountDownLatch(1);
            sseEventStreamService.addEventListener((cid, envelope) -> {
                if (cid.equals(clientId)) {
                    byClient.computeIfAbsent(cid, k -> new CopyOnWriteArrayList<>())
                        .add((String) envelope.get("processDefinitionId"));
                    firstDelivered.countDown();
                }
            });

            pushEvent(1, pdId);
            assertThat(firstDelivered.await(3, TimeUnit.SECONDS)).isTrue();

            // Act: revoke WITHOUT the event-driven hook (row delete only) —
            // the per-event re-resolution must still close the stream: the
            // 30s cache holds the OLD view, so evict it to simulate TTL
            // expiry deterministically (no 30s sleep in tests).
            processMemberRepository.deleteById(new ProcessMemberId(processId, user));
            var cacheField = SseEventStreamService.class.getDeclaredField("rightsCache");
            cacheField.setAccessible(true);
            @SuppressWarnings("unchecked")
            com.github.benmanes.caffeine.cache.Cache<?, ?> cache =
                (com.github.benmanes.caffeine.cache.Cache<?, ?>) cacheField.get(sseEventStreamService);
            cache.invalidateAll();

            pushEvent(2, pdId);
            Thread.sleep(500);

            // Assert: still exactly the pre-revoke event — the per-event
            // check closed the stream on the narrowed fresh view.
            assertThat(byClient.getOrDefault(clientId, List.of()))
                .as("per-event re-check must stop delivery after revoke")
                .containsExactly(pdId);
        } finally {
            sseEventStreamService.removeClient(clientId);
        }
    }

    @Test
    void perSubjectCap_exceeded_returns429AndReleasesSlotOnRemove() {
        // Arrange: cap of 1 for this subject (reflection — same pattern as PERF-6 maxClients).
        SseEventStreamService svc = sseEventStreamService;
        var field = org.springframework.test.util.ReflectionTestUtils.class;
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "maxClientsPerSubject", 1);

        Principal principal = new Principal.UserPrincipal(UUID.randomUUID(), "capped", "USER");
        try {
            String first = svc.registerClient(new SseEmitter(30 * 60 * 1000L), principal, null, null, null);
            try {
                // Act: second stream for the SAME subject → 429.
                org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        svc.registerClient(new SseEmitter(30 * 60 * 1000L), principal, null, null, null))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .satisfies(ex -> assertThat(
                        ((org.springframework.web.server.ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(429));

                // A DIFFERENT subject is unaffected by the first subject's cap.
                Principal other = new Principal.UserPrincipal(UUID.randomUUID(), "other", "USER");
                String otherClient = svc.registerClient(
                    new SseEmitter(30 * 60 * 1000L), other, null, null, null);
                svc.removeClient(otherClient);
            } finally {
                // Act: slot released on remove — re-register succeeds.
                svc.removeClient(first);
            }
            String again = svc.registerClient(new SseEmitter(30 * 60 * 1000L), principal, null, null, null);
            svc.removeClient(again);
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(svc, "maxClientsPerSubject", 10);
        }
    }

    @Test
    void logoutHook_loggedOutUserStreamClosedOnNextEvent() throws Exception {
        // Arrange: member + stream (principal mirrors the JWT identity).
        UUID processId = seedProcessWithDefinition("sseLogout");
        String pdId = pdIdOf(processId);
        UUID user = createUser("sse-logout");
        addMember(processId, user, "OPERATOR");
        String username = userRepository.findById(user).orElseThrow().getUsername();

        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(PASS);
        MvcResult login = mockMvc.perform(post("/auth/login")
                .content(mapper.writeValueAsString(dto))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();
        String token = mapper.readValue(
            login.getResponse().getContentAsString(), AuthResponse.class).getToken();

        Principal principal = new Principal.UserPrincipal(user, username, "USER");
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        String clientId = sseEventStreamService.registerClient(emitter, principal, null, null, null);
        try {
            Map<String, List<String>> byClient = new ConcurrentHashMap<>();
            CountDownLatch firstDelivered = new CountDownLatch(1);
            sseEventStreamService.addEventListener((cid, envelope) -> {
                if (cid.equals(clientId)) {
                    byClient.computeIfAbsent(cid, k -> new CopyOnWriteArrayList<>())
                        .add((String) envelope.get("processDefinitionId"));
                    firstDelivered.countDown();
                }
            });

            pushEvent(1, pdId);
            // Fan-out is async (sseExecutor) — wait, never assert immediately (P-10).
            assertThat(firstDelivered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(byClient.getOrDefault(clientId, List.of())).containsExactly(pdId);

            // Act: REAL logout over HTTP (bumps token_version + hook closes streams).
            mockMvc.perform(post("/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

            pushEvent(2, pdId);
            Thread.sleep(500);

            // Assert: dead credential → nothing more delivered.
            assertThat(byClient.getOrDefault(clientId, List.of()))
                .as("logged-out stream must NOT receive post-logout events")
                .containsExactly(pdId);
        } finally {
            sseEventStreamService.removeClient(clientId);
        }
    }
}
