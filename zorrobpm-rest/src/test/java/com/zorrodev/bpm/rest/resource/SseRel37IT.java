package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.entity.DomainEventEntity;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.DomainEventRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.Principal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REL-37: сквозной IT (критерии 1–3) на реальном контексте + реальной БД.
 *
 * <p>Критерий 1: событие из БД (sequence из IDENTITY) идёт через catchup с тем же
 * id/sequence; reconnect с границы продолжает дальше, уже отданное не дублируется.
 * Критерий 2: started с {@code elementId=null} + следующее событие — оба доходят.
 * Критерий 3: фильтр catchup совпадает с live; событие в окне catchup→live не
 * теряется и не дублируется (буфер + drain по границе).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SseRel37IT {

    @Autowired private DomainEventRepository domainEventRepository;
    @Autowired private SseEventStreamService sseEventStreamService;
    @Autowired private UiUserRepository uiUserRepository;
    @Autowired private com.zorrodev.bpm.engine.scheduler.FeedPositionAssigner feedPositionAssigner;

    private final List<Long> createdSequences = new ArrayList<>();

    private static Principal ADMIN;

    /**
     * WO-SEC-67: the stream principal must stand on a LIVE credential — seed
     * a real SUPER_ADMIN row once per class and build ADMIN from its id
     * (the liveness gate closes streams whose user row is missing).
     */
    @BeforeAll
    void seedLiveAdmin() {
        UUID id = UUID.randomUUID();
        UiUserEntity u = new UiUserEntity();
        u.setId(id);
        u.setUsername("sse-rel37-admin-" + id.toString().substring(0, 8));
        u.setPasswordHash("x");
        u.setFullName("SSE REL37 Admin");
        u.setEmail("sse-rel37-admin-" + id.toString().substring(0, 8) + "@example.com");
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
        if (!createdSequences.isEmpty()) {
            domainEventRepository.deleteAllById(createdSequences);
            createdSequences.clear();
        }
    }

    /** Emitter, захватывающий envelope через публичный builder.build(). */
    static class CapturingEmitter extends SseEmitter {
        final List<Map<String, Object>> envelopes = new ArrayList<>();

        CapturingEmitter() {
            super(60_000L);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void send(SseEventBuilder builder) {
            for (Object dwm : builder.build()) {
                try {
                    Object data = dwm.getClass().getMethod("getData").invoke(dwm);
                    if (data instanceof Map<?, ?> envelope) {
                        envelopes.add((Map<String, Object>) envelope);
                    }
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    private DomainEventEntity seed(String type, String elementId) {
        DomainEventEntity e = new DomainEventEntity();
        e.setId(UUID.randomUUID());
        e.setType(type);
        e.setVersion(1);
        e.setOccurredAt(Instant.now());
        e.setElementId(elementId);
        e.setData(Map.of());
        DomainEventEntity saved = domainEventRepository.save(e);
        createdSequences.add(saved.getSequence());
        // WO-REL-38: курсор — feed_position, её ставит джоб (без неё строка
        // невидима ни catchup, ни live).
        feedPositionAssigner.assignPendingPositions();
        return domainEventRepository.findById(saved.getSequence()).orElseThrow();
    }

    private static List<Long> positionsOf(List<Map<String, Object>> envelopes) {
        return envelopes.stream()
            .map(m -> ((Number) m.get("feedPosition")).longValue())
            .toList();
    }

    @Test
    void criterion1_reconnectContinuesFromBoundary_noDuplicates() {
        String type = "rel37.c1." + UUID.randomUUID();
        List<Long> mine = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            // WO-REL-38: граница и порядок — позиции курсора, не sequence.
            mine.add(seed(type, "el" + i).getFeedPosition());
        }
        long maxMine = mine.stream().mapToLong(Long::longValue).max().orElseThrow();

        CapturingEmitter first = new CapturingEmitter();
        long boundary = sseEventStreamService.sendCatchupEvents(first, 0L, ADMIN, null);

        assertThat(boundary).isGreaterThanOrEqualTo(maxMine);
        assertThat(positionsOf(first.envelopes)).containsAll(mine);
        // id события положительный и совпадает с позицией курсора в БД
        for (Map<String, Object> env : first.envelopes) {
            long fp = ((Number) env.get("feedPosition")).longValue();
            assertThat(fp).isPositive();
            assertThat(env.get("id")).isNotNull();
        }

        // Reconnect с границы: уже отданное (включая мои) не дублируется
        CapturingEmitter second = new CapturingEmitter();
        long boundary2 = sseEventStreamService.sendCatchupEvents(second, boundary, ADMIN, null);
        assertThat(boundary2).isEqualTo(boundary);
        assertThat(positionsOf(second.envelopes)).doesNotContainAnyElementsOf(mine);
    }

    @Test
    void criterion2_startedWithNullElementId_thenNext_bothDelivered() {
        String startedType = "rel37.c2.started." + UUID.randomUUID();
        String nextType = "rel37.c2.next." + UUID.randomUUID();
        DomainEventEntity started = seed(startedType, null);
        DomainEventEntity next = seed(nextType, "task1");

        CapturingEmitter emitter = new CapturingEmitter();
        long boundary = sseEventStreamService.sendCatchupEvents(
            emitter, started.getFeedPosition() - 1, ADMIN, null);

        List<Long> fps = positionsOf(emitter.envelopes);
        assertThat(fps).containsSubsequence(started.getFeedPosition(), next.getFeedPosition());
        assertThat(boundary).isGreaterThanOrEqualTo(next.getFeedPosition());
    }

    @Test
    void criterion3_filterParityAndNoLossAtSwitchover() throws Exception {
        String wanted = "rel37.c3.wanted." + UUID.randomUUID();
        String foreign = "rel37.c3.foreign." + UUID.randomUUID();
        List<Long> mine = new ArrayList<>();
        for (int i = 0; i < 3; i++) mine.add(seed(wanted, "el").getFeedPosition());
        seed(foreign, "el");
        seed(foreign, "el");

        // Catchup с фильтром: только свои
        CapturingEmitter catchup = new CapturingEmitter();
        long boundary = sseEventStreamService.sendCatchupEvents(
            catchup, 0L, ADMIN, null, wanted, null);
        assertThat(positionsOf(catchup.envelopes)).containsAll(mine);
        assertThat(catchup.envelopes).allMatch(e -> wanted.equals(e.get("type")));

        // Live-подписка ДО drain: событие в окне catchup→live буферизуется
        CapturingEmitter live = new CapturingEmitter();
        String clientId = sseEventStreamService.registerBufferedClient(
            live, ADMIN, wanted, null, null);
        try {
            // Новое событие уже после границы catchup
            DomainEventEntity fresh = seed(wanted, "el");
            String liveJson = "{\"sequence\":" + fresh.getSequence()
                + ",\"id\":\"" + fresh.getId() + "\",\"type\":\"" + wanted + "\","
                + "\"version\":1,\"occurredAt\":\"2026-09-16T00:00:00Z\",\"data\":{}}";
            sseEventStreamService.onDomainEvent(liveJson);
            // Чужой sequence (не наша строка) в live — мост отбрасывает сразу,
            // тем же фильтром его бы отбросил и dispatch.
            String foreignJson = "{\"sequence\":" + (fresh.getSequence() + 1_000_000)
                + ",\"id\":\"" + UUID.randomUUID() + "\",\"type\":\"" + foreign + "\","
                + "\"version\":1,\"occurredAt\":\"2026-09-16T00:00:00Z\",\"data\":{}}";
            sseEventStreamService.onDomainEvent(foreignJson);

            sseEventStreamService.drainBufferedClient(clientId, boundary);

            // Drain шлёт через sseExecutor асинхронно — ждём доставку (не фиксированный sleep).
            long deadline = System.currentTimeMillis() + 10_000;
            List<Long> liveSeqs = positionsOf(live.envelopes);
            while (!liveSeqs.contains(fresh.getFeedPosition()) && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
                liveSeqs = positionsOf(live.envelopes);
            }
            // Не потеряно (fresh доставлен), не задублировано (catchup-диапазон отброшен)
            assertThat(liveSeqs).contains(fresh.getFeedPosition());
            assertThat(liveSeqs).doesNotContainAnyElementsOf(mine);
            assertThat(live.envelopes).allMatch(e -> wanted.equals(e.get("type")));
        } finally {
            sseEventStreamService.removeClient(clientId);
        }
    }

    @Test
    void criterion3_backlog250_pagesThrough() {
        String type = "rel37.c3.bulk." + UUID.randomUUID();
        List<Long> mine = new ArrayList<>();
        for (int i = 0; i < 250; i++) mine.add(seed(type, "el").getFeedPosition());
        long maxMine = mine.stream().mapToLong(Long::longValue).max().orElseThrow();

        CapturingEmitter emitter = new CapturingEmitter();
        long boundary = sseEventStreamService.sendCatchupEvents(emitter, 0L, ADMIN, null, type, null);

        List<Long> delivered = positionsOf(emitter.envelopes).stream()
            .filter(mine::contains).toList();
        assertThat(delivered).hasSize(250);
        assertThat(boundary).isGreaterThanOrEqualTo(maxMine);
    }
}
