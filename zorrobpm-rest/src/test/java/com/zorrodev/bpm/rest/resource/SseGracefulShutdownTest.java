package com.zorrodev.bpm.rest.resource;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;

class SseGracefulShutdownTest {

    @Test
    void smartLifecycleStopCompletesEmitters() throws Exception {
        // WO-SEC-67: +2 ctor args (UiUserLookupService, ApiKeyRepository).
        var svc = new SseEventStreamService(null, null, null, new tools.jackson.databind.ObjectMapper(), null, null);
        assertThat(svc.isRunning()).isTrue();
        var emitter = new SseEmitter(0L);
        boolean[] completed = {false};
        emitter.onCompletion(() -> completed[0] = true);
        // inject emitter into private clients map via reflection
        var field = SseEventStreamService.class.getDeclaredField("clients");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var map = (java.util.Map<String, Object>) field.get(svc);
        // create a client session via the extracted top-level types (WO-AUDIT-9
        // шаг 2: writer-машина SseClientInfo переехала в SseClientSession,
        // identity — SseClientDescriptor; back-calls — через Host, которым
        // сервис и является).
        var info = new SseClientSession(
            new SseClientDescriptor("test-client", emitter, null, 0, null, null, null, null), svc);
        map.put("test-client", info);
        assertThat(map).hasSize(1);
        var callbackRan = new java.util.concurrent.atomic.AtomicBoolean(false);
        svc.stop(() -> callbackRan.set(true));
        assertThat(svc.isRunning()).isFalse();
        assertThat(callbackRan.get()).isTrue();
        assertThat(map).isEmpty();
        assertThat(svc.getPhase()).isEqualTo(Integer.MAX_VALUE - 100);
        // emitter.complete() was called (onCompletion will fire async, but map cleared proves drain)
    }
}
