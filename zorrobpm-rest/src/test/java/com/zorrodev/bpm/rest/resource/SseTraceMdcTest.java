package com.zorrodev.bpm.rest.resource;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zorrodev.bpm.rest.resource.EventAuthzResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * WO-OBS-8 (SSE point): the header-aware {@code onDomainEvent(body, headers)}
 * overload puts the envelope trace id + processInstanceId into MDC for the
 * fan-out, and restores the prior MDC afterwards (shared bridge thread).
 *
 * <p>Real service instance (only EventQueryService/authz are out of scope here —
 * no clients are registered, so no dispatch happens; the MDC window is observed
 * via a log record emitted inside the fan-out… except there is no per-dispatch
 * log on the empty-client path. Instead the test reads MDC through the
 * {@code EventDispatchListener} hook with one registered client whose emitter
 * send is exercised via the existing test scaffolding — simpler: assert on the
 * log pattern keys directly by capturing the MDC map inside a listener.
 */
class SseTraceMdcTest {

    private SseEventStreamService service;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger logger;
    private com.zorrodev.bpm.engine.service.EventQueryService eventQueryService;

    @BeforeEach
    void setUp() {
        EventAuthzResolver resolver = mock(EventAuthzResolver.class);
        // SUPER_ADMIN path: tests elsewhere stub readableRuntimePdIds to null
        // (see-all by contract); do the same so registerClient needs no rows.
        org.mockito.Mockito.when(resolver.readableRuntimePdIds(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(null);
        eventQueryService = mock(com.zorrodev.bpm.engine.service.EventQueryService.class);
        // WO-REL-38: live-мост резолвит позицию по sequence — здесь строка
        // "существует и назначена" (позиция = sequence, как после тика джоба).
        org.mockito.Mockito.when(eventQueryService.resolveFeedPositionBySequence(
            org.mockito.ArgumentMatchers.anyLong()))
            .thenAnswer(inv -> java.util.Optional.of(inv.getArgument(0)));
        service = new SseEventStreamService(eventQueryService,
            resolver, null, new tools.jackson.databind.ObjectMapper(), null, null);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger = (Logger) LoggerFactory.getLogger(SseEventStreamService.class);
        logger.addAppender(logAppender);
        logger.setLevel(Level.ALL);
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
        logAppender.stop();
        MDC.clear();
    }

    @Test
    void obs8_headersFeedMdc_bodyOnlyOverloadDoesNot() throws Exception {
        String traceId = "0af7651916cd43dd8448eb211c80319c";
        String traceParent = "00-" + traceId + "-b7ad6b7169203331-01";
        String body = "{\"sequence\":1,\"id\":\"" + UUID.randomUUID() + "\",\"type\":\"t\","
            + "\"processDefinitionId\":\"" + UUID.randomUUID() + "\","
            + "\"processInstanceId\":\"pi-body\","
            + "\"data\":{}}";

        // Capture MDC live inside the dispatch-listener callback (runs on the
        // calling thread within the MDC window — the only point values are live).
        var seen = new String[2];
        var latch = new java.util.concurrent.CountDownLatch(1);
        // Register a client directly: needs an emitter + principal; simpler to
        // observe via a listener added then triggered — but listeners only fire
        // per matching client. Register a minimal client through the public API.
        var emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(60_000L);
        // SUPER_ADMIN principal: skips credential-liveness/rights lookups (null
        // collaborators in this harness) straight to the fan-out.
        var principal = new com.zorrodev.bpm.engine.security.Principal.UserPrincipal(
            UUID.randomUUID(), "sse-obs8-user", "SUPER_ADMIN");
        String clientId = service.registerClient(emitter, principal, null, null, null);
        // Sanity: without a client the fan-out is a no-op — the registration must
        // have stuck (otherwise the listener below never fires and the test would
        // assert on silence, P-67).
        assertThat(clientId).isNotBlank();
        service.addEventListener((cid, envelope) -> {
            if (cid.equals(clientId)) {
                seen[0] = MDC.get("traceId");
                seen[1] = MDC.get("processInstanceId");
                latch.countDown();
            }
        });

        Map<String, Object> headers = Map.of(
            "traceparent", (Object) traceParent,
            "processInstanceId", (Object) "pi-header");
        service.onDomainEvent(body, headers);

        // The MDC guarantee worth pinning: the DISPATCH-side log line (emitted
        // synchronously inside the MDC window) carries traceId + PI. The async
        // listener callback (sseExecutor thread) cannot see the caller's MDC by
        // design (thread-local) — assert on the log RECORD's MDC map instead,
        // which logback snapshots at emit time on the calling thread.
        assertThat(latch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var dispatch = logAppender.list.stream()
            .filter(e -> e.getFormattedMessage().startsWith("SSE dispatch:"))
            .toList();
        assertThat(dispatch).as("one dispatch line inside the MDC window").hasSize(1);
        assertThat(dispatch.get(0).getMDCPropertyMap())
            .containsEntry("traceId", traceId)
            .containsEntry("processInstanceId", "pi-header");
        // Bridge-thread hygiene: nothing leaks past the call.
        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("processInstanceId")).isNull();
        service.removeClient(clientId);
    }

    @Test
    void obs8_bodyOnlyOverload_dispatchLineHasPiButNoTrace() {
        String body = "{\"sequence\":7,\"id\":\"" + UUID.randomUUID() + "\",\"type\":\"t\","
            + "\"processDefinitionId\":\"" + UUID.randomUUID() + "\","
            + "\"processInstanceId\":\"pi-body\","
            + "\"data\":{}}";
        var emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(60_000L);
        var principal = new com.zorrodev.bpm.engine.security.Principal.UserPrincipal(
            UUID.randomUUID(), "sse-obs8-b", "SUPER_ADMIN");
        String clientId = service.registerClient(emitter, principal, null, null, null);

        service.onDomainEvent(body);

        var dispatch = logAppender.list.stream()
            .filter(e -> e.getFormattedMessage().startsWith("SSE dispatch:"))
            .toList();
        assertThat(dispatch).hasSize(1);
        assertThat(dispatch.get(0).getMDCPropertyMap())
            .doesNotContainKey("traceId")
            .containsEntry("processInstanceId", "pi-body");
        service.removeClient(clientId);
    }
}
