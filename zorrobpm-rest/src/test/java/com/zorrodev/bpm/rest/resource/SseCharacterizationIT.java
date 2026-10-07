package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.entity.DomainEventEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.DomainEventRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.Principal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * WO-AUDIT-9 (a): characterization-тесты поведения {@link SseEventStreamService}
 * на ЖИВОМ Spring-контексте (реальный бин, реальный {@link EventAuthzResolver},
 * реальная БД) — сняты ДО рефакторинга (дерево == master по прод-коду) и обязаны
 * остаться зелёными после каждого шага распила без правок ассертов.
 *
 * <p>Покрытие по механизмам WO: (1) порядок + идемпотентность доставки,
 * (2) gap после дыры в {@code feed_position}, (3) выпуск по водяному знаку
 * (порядок commit, не arrival), (4) replay по курсору (Last-Event-ID),
 * (5) лимиты соединений (глобальный + per-subject), (6) авторизация по
 * получателю, (7) закрытие соединений (слот освобождается), (8) backpressure
 * (close-on-overflow), (9) контракт провода (id/name/data + путь контроллера).
 *
 * <p>Мутационные дискриминаторы (WO-AUDIT-9 в): T2 (убрать gap-таймер →
 * событие D никогда не доставляется свежему клиенту), T6 (убрать authz-гейт →
 * outsider получает чужое событие).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SseCharacterizationIT {

    @Autowired private SseEventStreamService sseEventStreamService;
    @Autowired private com.zorrodev.bpm.engine.service.EventQueryService eventQueryService;
    @Autowired private EventAuthzResolver eventAuthzResolver;
    @Autowired private com.zorrodev.bpm.engine.security.UiUserLookupService uiUserLookupService;
    @Autowired private com.zorrodev.bpm.engine.service.ApiKeyService apiKeyService;
    @Autowired private tools.jackson.databind.ObjectMapper objectMapper;
    @Autowired private DomainEventRepository domainEventRepository;
    @Autowired private UiUserRepository uiUserRepository;
    @Autowired private ProcessRepository processRepository;
    @Autowired private ProcessDefinitionRepository processDefinitionRepository;
    @Autowired private ProcessMemberRepository processMemberRepository;
    @Autowired private com.zorrodev.bpm.engine.scheduler.FeedPositionAssigner feedPositionAssigner;

    private static Principal ADMIN;

    private final List<Long> createdSequences = new ArrayList<>();
    private final List<ClientRef> openedClients = new CopyOnWriteArrayList<>();
    private final List<SseEventStreamService> freshServices = new CopyOnWriteArrayList<>();

    record ClientRef(SseEventStreamService svc, String clientId) {
    }

    /**
     * Свежий сервис с РЕАЛЬНЫМИ коллабораторами контекста (тот же wiring, что
     * прод-бин; только брокер отсутствует — rabbitAdmin null, как в юнит-харнессах).
     * Каждому live-тесту — свой экземпляр: сиквенсор/водяной знак глобальны на
     * инстанс, общий синглтон-би́н тащил бы знак между тестами и делал gap-тесты
     * зависимыми от порядка выполнения.
     */
    private SseEventStreamService freshService() {
        SseEventStreamService svc = new SseEventStreamService(
            eventQueryService, eventAuthzResolver, null, objectMapper,
            uiUserLookupService, apiKeyService);
        freshServices.add(svc);
        return svc;
    }

    private void pushLive(SseEventStreamService svc, DomainEventEntity row) {
        svc.onDomainEvent(
            "{\"sequence\":" + row.getSequence()
                + ",\"id\":\"" + row.getId() + "\",\"type\":\"" + row.getType() + "\","
                + "\"version\":1,\"occurredAt\":\"2026-09-16T00:00:00Z\",\"data\":{}}");
    }

    private void pushLive(SseEventStreamService svc, DomainEventEntity row, String pdId) {
        svc.onDomainEvent(
            "{\"sequence\":" + row.getSequence()
                + ",\"id\":\"" + row.getId() + "\",\"type\":\"" + row.getType() + "\","
                + "\"version\":1,\"occurredAt\":\"2026-09-16T00:00:00Z\","
                + "\"processDefinitionId\":\"" + pdId + "\","
                + "\"processInstanceId\":\"" + UUID.randomUUID() + "\",\"data\":{}}");
    }

    @BeforeAll
    void seedLiveAdmin() {
        UUID id = UUID.randomUUID();
        UiUserEntity u = new UiUserEntity();
        u.setId(id);
        u.setUsername("sse-char-admin-" + id.toString().substring(0, 8));
        u.setPasswordHash("x");
        u.setFullName("SSE Char Admin");
        u.setEmail("sse-char-admin-" + id.toString().substring(0, 8) + "@example.com");
        u.setRole("SUPER_ADMIN");
        u.setUserType("HUMAN");
        u.setActive(true);
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        uiUserRepository.save(u);
        ADMIN = new Principal.UserPrincipal(id, "admin", "SUPER_ADMIN");
    }

    @AfterEach
    void cleanup() {
        sseEventStreamService.clearEventListeners();
        for (ClientRef ref : openedClients) {
            try {
                ref.svc().removeClient(ref.clientId());
            } catch (Exception ignore) {
            }
        }
        openedClients.clear();
        for (SseEventStreamService svc : freshServices) {
            try {
                svc.clearEventListeners();
                svc.stop();
            } catch (Exception ignore) {
            }
        }
        freshServices.clear();
        if (!createdSequences.isEmpty()) {
            domainEventRepository.deleteAllById(createdSequences);
            createdSequences.clear();
        }
        org.slf4j.MDC.clear();
    }

    /** Сохранить ряд БЕЗ позиции (джоб в test-профиле не тикает — дыра детерминирована). */
    private DomainEventEntity saveUnpositioned(String type) {
        DomainEventEntity e = new DomainEventEntity();
        e.setId(UUID.randomUUID());
        e.setType(type);
        e.setVersion(1);
        e.setOccurredAt(Instant.now());
        e.setData(Map.of());
        DomainEventEntity saved = domainEventRepository.save(e);
        createdSequences.add(saved.getSequence());
        return domainEventRepository.findById(saved.getSequence()).orElseThrow();
    }

    /** Сохранить ряд И назначить позицию (виден и live-мосту, и catchup). */
    private DomainEventEntity seedPositioned(String type) {
        DomainEventEntity saved = saveUnpositioned(type);
        feedPositionAssigner.assignPendingPositions();
        return domainEventRepository.findById(saved.getSequence()).orElseThrow();
    }

    private void pushLive(DomainEventEntity row) {
        pushLive(sseEventStreamService, row);
    }

    private void pushLive(DomainEventEntity row, String pdId) {
        pushLive(sseEventStreamService, row, pdId);
    }

    /** Live-клиент + коллектор доставок по clientId (через listener — только РЕАЛЬНЫЕ send). */
    private String openLiveClient(SseEventStreamService svc, Principal principal,
            Map<String, List<Map<String, Object>>> byClient) {
        SseEmitter emitter = new SseEmitter(60_000L);
        String clientId = svc.registerBufferedClient(
            emitter, principal, null, null, null);
        openedClients.add(new ClientRef(svc, clientId));
        svc.addEventListener((cid, envelope) -> {
            if (cid.equals(clientId)) {
                byClient.computeIfAbsent(cid, k -> new CopyOnWriteArrayList<>()).add(envelope);
            }
        });
        svc.drainBufferedClient(clientId, 0L);
        return clientId;
    }

    private static List<Long> cursorsOf(List<Map<String, Object>> envelopes) {
        return envelopes.stream()
            .map(m -> ((Number) m.get("feedPosition")).longValue())
            .toList();
    }

    private UUID seedLiveUser(String prefix, String role) {
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

    // (1) порядок + идемпотентность: три события в порядке commit — доставлены
    // в том же порядке ровно по одному разу (тишина после — тоже утверждение).
    @Test
    void char1_orderAndIdempotency_threeEventsDeliveredOnceInOrder() throws Exception {
        SseEventStreamService svc = freshService();
        String type = "char1." + UUID.randomUUID();
        DomainEventEntity a = seedPositioned(type);
        DomainEventEntity b = seedPositioned(type);
        DomainEventEntity c = seedPositioned(type);

        Map<String, List<Map<String, Object>>> byClient = new ConcurrentHashMap<>();
        String clientId = openLiveClient(svc, ADMIN, byClient);
        try {
            pushLive(svc, a);
            pushLive(svc, b);
            pushLive(svc, c);
            List<Long> expected = List.of(a.getFeedPosition(), b.getFeedPosition(), c.getFeedPosition());
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(cursorsOf(byClient.getOrDefault(clientId, List.of())))
                    .containsExactlyElementsOf(expected));
            // Идемпотентность: окно тишины — дублей нет.
            Thread.sleep(1500);
            assertThat(cursorsOf(byClient.getOrDefault(clientId, List.of())))
                .as("no duplicates after quiet window")
                .containsExactlyElementsOf(expected);
        } finally {
            svc.removeClient(clientId);
        }
    }

    // (2) gap после дыры: B сохранён, но live не приходит и позиции не получает
    // (джоб в test-профиле молчит); C ждёт B до бюджета gap-таймера, затем дыра
    // закрывается: клиент закрывается (complete — браузер переподключится,
    // reconnect heals через catchup), C и позже D забираются catchup'ом.
    // Мутация «убрать gap-таймер»: complete не fires, heap stuck → RED.
    @Test
    void char2_gapAfterHole_clientClosedAndReconnectHeals() throws Exception {
        SseEventStreamService svc = freshService();
        String type = "char2." + UUID.randomUUID();
        Object savedDelay = ReflectionTestUtils.getField(svc, "deferredCursorDelayMs");
        ReflectionTestUtils.setField(svc, "deferredCursorDelayMs", 20L);
        try {
            DomainEventEntity a = seedPositioned(type);
            DomainEventEntity b = saveUnpositioned(type);
            DomainEventEntity c = saveUnpositioned(type);

            CloseWatchingEmitter watching = new CloseWatchingEmitter();
            String oldId = svc.registerBufferedClient(watching, ADMIN, null, null, null);
            openedClients.add(new ClientRef(svc, oldId));
            Map<String, List<Map<String, Object>>> byClient = new ConcurrentHashMap<>();
            svc.addEventListener((cid, envelope) -> {
                if (cid.equals(oldId)) {
                    byClient.computeIfAbsent(cid, k -> new CopyOnWriteArrayList<>()).add(envelope);
                }
            });
            svc.drainBufferedClient(oldId, 0L);
            try {
                pushLive(svc, a);
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(cursorsOf(byClient.getOrDefault(oldId, List.of())))
                        .containsExactly(a.getFeedPosition()));

                // C — в мост, затем позиция (B при этом тоже позиционируется,
                // но live не приходит: дыра строго между A и C).
                pushLive(svc, c);
                feedPositionAssigner.assignPendingPositions();
                long fpB = domainEventRepository.findById(b.getSequence()).orElseThrow().getFeedPosition();
                long fpC = domainEventRepository.findById(c.getSequence()).orElseThrow().getFeedPosition();

                // Gap-таймер закрывает поток (бюджет 30×20мс + margin):
                // без таймера heap stuck навсегда и complete не fires (мутация RED).
                assertThat(watching.closed.await(10, TimeUnit.SECONDS))
                    .as("gap-close must complete the holed stream (reconnect heals)")
                    .isTrue();
                // Старый клиент закрыт gap-close: после A ничего не доставлено.
                Thread.sleep(500);
                assertThat(cursorsOf(byClient.getOrDefault(oldId, List.of())))
                    .as("gap-closed client receives nothing after the hole")
                    .containsExactly(a.getFeedPosition());

                // Потери нет: reconnect-catchup от курсора A забирает B и C.
                CollectingEmitter catcher = new CollectingEmitter();
                long boundary = svc.sendCatchupEvents(
                    catcher, a.getFeedPosition(), ADMIN, null, type, null);
                assertThat(cursorsOf(catcher.envelopes))
                    .as("reconnect heals the hole (B) and the gap-closed C")
                    .containsSubsequence(fpB, fpC);
                assertThat(boundary).isGreaterThanOrEqualTo(fpC);
            } finally {
                svc.removeClient(oldId);
            }
        } finally {
            ReflectionTestUtils.setField(svc, "deferredCursorDelayMs", savedDelay);
        }
    }

    // (3) водяной знак: прибытие [X, C, B, decoy] при commit-порядке
    // [X, decoy, B, C] — доставка строго в порядке commit (позиций), не прибытия.
    @Test
    void char3_watermark_releasesInCommitOrderNotArrivalOrder() {
        SseEventStreamService svc = freshService();
        String type = "char3." + UUID.randomUUID();
        DomainEventEntity x = saveUnpositioned(type);
        DomainEventEntity decoy = saveUnpositioned(type);
        DomainEventEntity b = saveUnpositioned(type);
        DomainEventEntity c = saveUnpositioned(type);
        feedPositionAssigner.assignPendingPositions();
        x = domainEventRepository.findById(x.getSequence()).orElseThrow();
        decoy = domainEventRepository.findById(decoy.getSequence()).orElseThrow();
        b = domainEventRepository.findById(b.getSequence()).orElseThrow();
        c = domainEventRepository.findById(c.getSequence()).orElseThrow();

        Map<String, List<Map<String, Object>>> byClient = new ConcurrentHashMap<>();
        String clientId = openLiveClient(svc, ADMIN, byClient);
        try {
            pushLive(svc, x);
            pushLive(svc, c);
            pushLive(svc, b);
            pushLive(svc, decoy);
            List<Long> expected = List.of(
                x.getFeedPosition(), decoy.getFeedPosition(), b.getFeedPosition(), c.getFeedPosition());
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(cursorsOf(byClient.getOrDefault(clientId, List.of())))
                    .containsExactlyElementsOf(expected));
        } finally {
            svc.removeClient(clientId);
        }
    }

    // (4) replay по курсору: catchup от 0 — всё по порядку; от середины — только
    // суффикс; от границы — пусто (Last-Event-ID round-trip).
    @Test
    void char4_replayByCursor_suffixOnlyAndNoDuplicates() {
        String type = "char4." + UUID.randomUUID();
        DomainEventEntity a = seedPositioned(type);
        DomainEventEntity b = seedPositioned(type);
        DomainEventEntity c = seedPositioned(type);

        CollectingEmitter first = new CollectingEmitter();
        long boundary = sseEventStreamService.sendCatchupEvents(first, 0L, ADMIN, null, type, null);
        assertThat(cursorsOf(first.envelopes))
            .containsSubsequence(a.getFeedPosition(), b.getFeedPosition(), c.getFeedPosition());
        assertThat(boundary).isGreaterThanOrEqualTo(c.getFeedPosition());

        CollectingEmitter suffix = new CollectingEmitter();
        long boundary2 = sseEventStreamService.sendCatchupEvents(
            suffix, b.getFeedPosition(), ADMIN, null, type, null);
        assertThat(cursorsOf(suffix.envelopes)).containsExactly(c.getFeedPosition());
        assertThat(boundary2).isEqualTo(boundary);

        CollectingEmitter empty = new CollectingEmitter();
        long boundary3 = sseEventStreamService.sendCatchupEvents(
            empty, boundary, ADMIN, null, type, null);
        assertThat(empty.envelopes).isEmpty();
        assertThat(boundary3).isEqualTo(boundary);
    }

    // (5) лимиты: глобальный maxClients и per-subject cap отвечают 429;
    // после removeClient слот возвращается (повторная регистрация успешна).
    @Test
    void char5_connectionLimits_globalAndPerSubject429AndSlotReleased() {
        Object savedMax = ReflectionTestUtils.getField(sseEventStreamService, "maxClients");
        Object savedPerSubject = ReflectionTestUtils.getField(sseEventStreamService, "maxClientsPerSubject");
        String first = null;
        try {
            ReflectionTestUtils.setField(sseEventStreamService, "maxClients", 1);
            first = sseEventStreamService.registerClient(new SseEmitter(60_000L), ADMIN, null, null, null);
            openedClients.add(new ClientRef(sseEventStreamService, first));
            assertThatThrownBy(() -> sseEventStreamService.registerClient(
                    new SseEmitter(60_000L), ADMIN, null, null, null))
                .as("global cap exceeded")
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        } finally {
            if (first != null) {
                sseEventStreamService.removeClient(first);
                final String firstId = first;
                openedClients.removeIf(ref -> ref.clientId().equals(firstId));
            }
            ReflectionTestUtils.setField(sseEventStreamService, "maxClients", savedMax);
        }

        String a = null;
        try {
            ReflectionTestUtils.setField(sseEventStreamService, "maxClientsPerSubject", 1);
            a = sseEventStreamService.registerClient(new SseEmitter(60_000L), ADMIN, null, null, null);
            openedClients.add(new ClientRef(sseEventStreamService, a));
            assertThatThrownBy(() -> sseEventStreamService.registerClient(
                    new SseEmitter(60_000L), ADMIN, null, null, null))
                .as("per-subject cap exceeded")
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
            // Слот освобождается: снять A — новая регистрация успешна.
            sseEventStreamService.removeClient(a);
            final String removedA = a;
            openedClients.removeIf(ref -> ref.clientId().equals(removedA));
            a = null;
            String again = sseEventStreamService.registerClient(
                new SseEmitter(60_000L), ADMIN, null, null, null);
            openedClients.add(new ClientRef(sseEventStreamService, again));
            assertThat(again).isNotBlank();
        } finally {
            if (a != null) {
                sseEventStreamService.removeClient(a);
                final String left = a;
                openedClients.removeIf(ref -> ref.clientId().equals(left));
            }
            ReflectionTestUtils.setField(sseEventStreamService, "maxClientsPerSubject", savedPerSubject);
        }
    }

    // (6) авторизация по получателю: участник процесса получает событие,
    // посторонний (живой, но без membership) — нет.
    // Мутация «убрать authz-гейт»: outsider получает событие → RED.
    @Test
    void char6_authzByRecipient_memberReceivesOutsiderDoesNot() throws Exception {
        SseEventStreamService svc = freshService();
        String key = "char6_" + UUID.randomUUID();
        UUID processId = UUID.randomUUID();
        ProcessEntity proc = new ProcessEntity();
        proc.setId(processId);
        proc.setDefinitionKey(key);
        proc.setName("char6 proc");
        proc.setCreatedAt(Instant.now());
        processRepository.save(proc);
        ProcessDefinitionEntity pd = new ProcessDefinitionEntity();
        pd.setId(UUID.randomUUID());
        pd.setKey(key);
        pd.setName("char6 def");
        pd.setVersion(1);
        pd.setSha256("sha-char6-" + UUID.randomUUID());
        pd.setCreatedAt(Instant.now());
        processDefinitionRepository.save(pd);
        String pdId = pd.getId().toString();

        UUID memberId = seedLiveUser("char6member", "USER");
        ProcessMemberEntity pm = new ProcessMemberEntity();
        pm.setProcessId(processId);
        pm.setUserId(memberId);
        pm.setRole("VIEWER");
        pm.setAddedBy(memberId);
        pm.setAddedAt(Instant.now());
        processMemberRepository.save(pm);
        UUID outsiderId = seedLiveUser("char6outsider", "USER");
        Principal member = new Principal.UserPrincipal(memberId, "char6member", "USER");
        Principal outsider = new Principal.UserPrincipal(outsiderId, "char6outsider", "USER");

        String type = "char6." + UUID.randomUUID();
        DomainEventEntity row = seedPositioned(type);

        Map<String, List<Map<String, Object>>> byClient = new ConcurrentHashMap<>();
        String memberId1 = openLiveClient(svc, member, byClient);
        String outsiderId1 = openLiveClient(svc, outsider, byClient);
        CountDownLatch anyOutsiderDispatch = new CountDownLatch(1);
        svc.addEventListener((cid, envelope) -> {
            if (cid.equals(outsiderId1)) {
                anyOutsiderDispatch.countDown();
            }
        });
        try {
            pushLive(svc, row, pdId);
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(byClient.getOrDefault(memberId1, List.of())).hasSize(1));
            assertThat(anyOutsiderDispatch.await(2, TimeUnit.SECONDS))
                .as("outsider must receive nothing (fail-closed)")
                .isFalse();
            assertThat(byClient.getOrDefault(outsiderId1, List.of())).isEmpty();
        } finally {
            svc.removeClient(memberId1);
            svc.removeClient(outsiderId1);
        }
    }

    // (7) закрытие: после removeClient доставки старому id нет; слоты не текут
    // (N последовательных регистраций при cap=1 успешны).
    @Test
    void char7_close_stopsDeliveryAndDoesNotLeakSlots() throws Exception {
        SseEventStreamService svc = freshService();
        Object savedPerSubject = ReflectionTestUtils.getField(svc, "maxClientsPerSubject");
        String type = "char7." + UUID.randomUUID();
        DomainEventEntity first = seedPositioned(type);

        Map<String, List<Map<String, Object>>> byClient = new ConcurrentHashMap<>();
        String clientId = openLiveClient(svc, ADMIN, byClient);
        try {
            pushLive(svc, first);
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(byClient.getOrDefault(clientId, List.of())).hasSize(1));
        } finally {
            svc.removeClient(clientId);
            openedClients.removeIf(ref -> ref.clientId().equals(clientId));
        }
        CountDownLatch lateDispatch = new CountDownLatch(1);
        svc.addEventListener((cid, envelope) -> {
            if (cid.equals(clientId)) {
                lateDispatch.countDown();
            }
        });
        DomainEventEntity second = seedPositioned(type);
        pushLive(svc, second);
        assertThat(lateDispatch.await(2, TimeUnit.SECONDS))
            .as("closed client receives nothing")
            .isFalse();

        try {
            ReflectionTestUtils.setField(svc, "maxClientsPerSubject", 1);
            for (int i = 0; i < 3; i++) {
                String id = svc.registerClient(
                    new SseEmitter(60_000L), ADMIN, null, null, null);
                svc.removeClient(id);
            }
        } finally {
            ReflectionTestUtils.setField(svc, "maxClientsPerSubject", savedPerSubject);
        }
    }

    // (8) backpressure: медленный клиент + поток быстрее очереди — поток
    // закрывается политикой переполнения (complete), доставлено меньше pushed.
    @Test
    void char8_backpressure_slowClientClosedByOverflowPolicy() throws Exception {
        SseEventStreamService svc = freshService();
        Object savedCap = ReflectionTestUtils.getField(svc, "perClientQueueEvents");
        Object savedTimeout = ReflectionTestUtils.getField(svc, "sendTimeoutMs");
        String type = "char8." + UUID.randomUUID();
        List<DomainEventEntity> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            rows.add(saveUnpositioned(type));
        }
        feedPositionAssigner.assignPendingPositions();
        rows.replaceAll(r -> domainEventRepository.findById(r.getSequence()).orElseThrow());
        try {
            ReflectionTestUtils.setField(svc, "perClientQueueEvents", 8);
            ReflectionTestUtils.setField(svc, "sendTimeoutMs", 60_000L);
            SlowProbeEmitter slow = new SlowProbeEmitter();
            String clientId = svc.registerBufferedClient(
                slow, ADMIN, null, null, null);
            openedClients.add(new ClientRef(svc, clientId));
            svc.drainBufferedClient(clientId, 0L);
            for (DomainEventEntity row : rows) {
                pushLive(svc, row);
            }
            assertThat(slow.completed.await(60, TimeUnit.SECONDS))
                .as("overflow policy must close the slow stream")
                .isTrue();
            Thread.sleep(500);
            assertThat(slow.delivered.size())
                .as("stream cut short by policy, not fully delivered")
                .isLessThan(rows.size());
        } finally {
            ReflectionTestUtils.setField(svc, "perClientQueueEvents", savedCap);
            ReflectionTestUtils.setField(svc, "sendTimeoutMs", savedTimeout);
        }
    }

    // (9) контракт провода: catchup-событие несёт id (курсор строкой),
    // name (тип) и data (envelope); путь контроллера — /events/stream.
    // id/name читаются из ТОГО ЖЕ поля sb builder'а, которое Spring сериализует
    // в сокет (прецедент — SseRel57HeartbeatTest.builderText; байткод
    // SseEventBuilderImpl: id() пишет "id:…\n", name() — "event:…\n").
    @Test
    void char9_contract_eventWireFormatAndControllerPath() throws Exception {
        String type = "char9." + UUID.randomUUID();
        DomainEventEntity row = seedPositioned(type);

        CollectingEmitter catcher = new CollectingEmitter();
        sseEventStreamService.sendCatchupEvents(catcher, 0L, ADMIN, null, type, null);
        assertThat(catcher.envelopes).isNotEmpty();
        assertThat(catcher.wireTexts).isNotEmpty();
        // Провод склеен из чанков (id/event/data идут отдельными String-чанками
        // dataToSend — см. класс коллектора): ищем маркеры в целом тексте.
        String wire = String.join("", catcher.wireTexts);
        long cursor = ((Number) catcher.envelopes.get(0).get("feedPosition")).longValue();
        assertThat(wire).as("SSE wire carries id = cursor").contains("id:" + cursor);
        assertThat(wire).as("SSE wire carries event = type").contains("event:" + type);

        var mapping = SseEventStreamController.class.getAnnotation(
            org.springframework.web.bind.annotation.RequestMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly("/events/stream");
        var method = SseEventStreamController.class.getMethod("stream",
            String.class, String.class, String.class, String.class,
            jakarta.servlet.http.HttpServletRequest.class);
        var get = method.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class);
        assertThat(get).isNotNull();
        assertThat(get.produces()).contains(org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE);
    }

    /** Emitter, читающий построенный builder (та же техника, что Rel37IT.CapturingEmitter). */
    static class CollectingEmitter extends SseEmitter {
        final List<Map<String, Object>> envelopes = new CopyOnWriteArrayList<>();
        final List<String> wireTexts = new CopyOnWriteArrayList<>();

        CollectingEmitter() {
            super(60_000L);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void send(SseEventBuilder builder) {
            // Байткод SseEventBuilderImpl: id()/name()/reconnectTime() пишут в
            // sb, но data() СНАЧАЛА уносит накопленное (saveAppendedText) первым
            // чанком в dataToSend — живой sb после build держит только хвост
            // ("retry:3000"). Настоящий провод — первый String-чанк dataToSend
            // ("id:…\nevent:…\n"), он же уходит в сокет первым.
            for (Object dwm : builder.build()) {
                try {
                    Object data = dwm.getClass().getMethod("getData").invoke(dwm);
                    if (data instanceof String text) {
                        wireTexts.add(text);
                    } else if (data instanceof Map<?, ?> envelope) {
                        envelopes.add((Map<String, Object>) envelope);
                    }
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    /** Emitter, ловящий complete (факт закрытия потока — gap-close/overflow/revoke). */
    static class CloseWatchingEmitter extends SseEmitter {
        final CountDownLatch closed = new CountDownLatch(1);

        CloseWatchingEmitter() {
            super(60_000L);
        }

        @Override
        public void complete() {
            closed.countDown();
            super.complete();
        }
    }

    /** Медленный emitter: send реально занимает время + ловит complete (политика переполнения). */
    static class SlowProbeEmitter extends SseEmitter {        final List<Map<String, Object>> delivered = new CopyOnWriteArrayList<>();
        final CountDownLatch completed = new CountDownLatch(1);

        SlowProbeEmitter() {
            super(60_000L);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void send(SseEventBuilder builder) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            for (Object dwm : builder.build()) {
                try {
                    Object data = dwm.getClass().getMethod("getData").invoke(dwm);
                    if (data instanceof Map<?, ?> envelope) {
                        delivered.add((Map<String, Object>) envelope);
                    }
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
        }

        @Override
        public void complete() {
            completed.countDown();
            super.complete();
        }
    }
}
