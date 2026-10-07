package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.event.SseLiveCursorTracker;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.EventQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;

/**
 * WO-AUDIT-7: проводка пина живых курсоров через SSE-путь (REST-слой).
 *
 * <p>Инвариант, который держит retention: пока подписчик с курсором внутри
 * окна удаления жив, проход не трогает строки выше минимального активного
 * курсора. Здесь доказывается REST-половина: track (контроллер ДО catchup) →
 * drain (граница catchup) → live (рассылка) → untrack (disconnect).
 * SQL-половина (пин в предикате) — в {@code EventsOutboxRetentionPgIT}.
 */
@ExtendWith(MockitoExtension.class)
class SseRetentionPinWiringTest {

    @Mock private EventQueryService eventQueryService;
    @Mock private EventAuthzResolver eventAuthzResolver;

    private final List<SseEventStreamService> services = new java.util.concurrent.CopyOnWriteArrayList<>();

    private SseLiveCursorTracker tracker;
    private SseEventStreamService service;

    private void setUpService() {
        tracker = new SseLiveCursorTracker();
        lenient().when(eventAuthzResolver.readableRuntimePdIds(any(), any())).thenReturn(null);
        lenient().when(eventQueryService.resolveFeedPositionBySequence(anyLong()))
            .thenAnswer(inv -> java.util.Optional.of(inv.getArgument(0)));
        lenient().when(eventQueryService.eventSequenceExists(anyLong())).thenReturn(true);
        service = new SseEventStreamService(eventQueryService, eventAuthzResolver, null,
            new tools.jackson.databind.ObjectMapper(), null, null, null, tracker);
        services.add(service);
    }

    @AfterEach
    void tearDown() {
        for (SseEventStreamService svc : services) {
            try {
                svc.clearEventListeners();
            } catch (Exception ignore) {
            }
        }
        services.clear();
        org.slf4j.MDC.clear();
    }

    private static Principal admin() {
        return new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
    }

    static class CapturingEmitter extends SseEmitter {
        final List<Map<String, Object>> delivered = new java.util.concurrent.CopyOnWriteArrayList<>();

        CapturingEmitter() {
            super(60_000L);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void send(SseEventBuilder builder) throws java.io.IOException {
            for (Object dwm : builder.build()) {
                try {
                    Object data = dwm.getClass().getMethod("getData").invoke(dwm);
                    if (data instanceof Map<?, ?> envelope) {
                        delivered.add((Map<String, Object>) envelope);
                    }
                } catch (ReflectiveOperationException e) {
                    throw new java.io.IOException(e);
                }
            }
        }
    }

    private static String body(long sequence, String type) {
        return "{\"sequence\":" + sequence + ",\"id\":\"" + UUID.randomUUID() + "\","
            + "\"type\":\"" + type + "\",\"version\":1,\"occurredAt\":\"2026-09-25T00:00:00Z\","
            + "\"processDefinitionId\":\"" + UUID.randomUUID() + "\","
            + "\"processInstanceId\":\"" + UUID.randomUUID() + "\",\"data\":{}}";
    }

    @Test
    void trackCatchupCursor_pinsSlowestClient() {
        setUpService();
        String slow = service.registerBufferedClient(new CapturingEmitter(), admin(), null, null, null);
        String fast = service.registerBufferedClient(new CapturingEmitter(), admin(), null, null, null);
        service.trackCatchupCursor(slow, 5L);
        service.trackCatchupCursor(fast, 9L);
        service.drainBufferedClient(slow, 0L);
        service.drainBufferedClient(fast, 0L);

        assertThat(tracker.minActiveCursor()).hasValue(5L);
    }

    @Test
    void drainBoundary_advancesPin_liveAdvancesFurther_disconnectReleases() throws Exception {
        setUpService();
        CapturingEmitter emitter = new CapturingEmitter();
        String id = service.registerBufferedClient(emitter, admin(), null, null, null);
        service.trackCatchupCursor(id, 5L);
        service.drainBufferedClient(id, 5L);
        assertThat(tracker.minActiveCursor()).hasValue(5L);

        // Live-рассылка двигает пин вперёд (позиция мока = sequence; первое
        // событие дырой не считается — уходит сразу, но pump асинхронен).
        service.onDomainEvent(body(12L, "audit7.live." + UUID.randomUUID()));
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10))
            .untilAsserted(() -> {
                assertThat(emitter.delivered).hasSize(1);
                assertThat(tracker.minActiveCursor()).hasValue(12L);
            });

        service.removeClient(id);
        assertThat(tracker.minActiveCursor()).isEmpty();
    }

    @Test
    void noHeader_noPin_plainRegistrationsDoNotPin() {
        setUpService();
        String id = service.registerBufferedClient(new CapturingEmitter(), admin(), null, null, null);
        service.drainBufferedClient(id, 0L);
        assertThat(tracker.minActiveCursor()).isEmpty();
        service.removeClient(id);
    }

    @Test
    void slowestDisconnect_pinJumpsToNext() {
        setUpService();
        String slow = service.registerBufferedClient(new CapturingEmitter(), admin(), null, null, null);
        String fast = service.registerBufferedClient(new CapturingEmitter(), admin(), null, null, null);
        service.trackCatchupCursor(slow, 5L);
        service.trackCatchupCursor(fast, 9L);

        service.removeClient(slow);

        assertThat(tracker.minActiveCursor()).hasValue(9L);
        service.removeClient(fast);
        assertThat(tracker.minActiveCursor()).isEmpty();
    }
}
