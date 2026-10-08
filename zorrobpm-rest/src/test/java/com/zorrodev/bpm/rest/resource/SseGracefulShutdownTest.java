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
        // register a session through the extracted registry (WO-AUDIT-9 шаг 3:
        // реестр — SseSessionRegistry; back-calls сессии — через Host, которым
        // сервис и является).
        var info = new SseClientSession(
            new SseClientDescriptor("test-client", emitter, null, 0, null, null, null, null), svc);
        var rf = SseEventStreamService.class.getDeclaredField("sessionRegistry");
        rf.setAccessible(true);
        var registry = (SseSessionRegistry) rf.get(svc);
        registry.add(info, "test-subject");
        assertThat(registry.size()).isEqualTo(1);
        var callbackRan = new java.util.concurrent.atomic.AtomicBoolean(false);
        svc.stop(() -> callbackRan.set(true));
        assertThat(svc.isRunning()).isFalse();
        assertThat(callbackRan.get()).isTrue();
        assertThat(registry.size()).isEqualTo(0);
        assertThat(svc.getPhase()).isEqualTo(Integer.MAX_VALUE - 100);
        // emitter.complete() was called (onCompletion will fire async, but map cleared proves drain)
    }
}
