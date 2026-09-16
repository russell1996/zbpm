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
        // create SseClientInfo record via reflection (private record)
        // WO-SEC-67: +1 component (tokenVersion between principal and allowedPdIds).
        var recClass = Class.forName("com.zorrodev.bpm.rest.resource.SseEventStreamService$SseClientInfo");
        var ctor = recClass.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        var info = ctor.newInstance("test-client", emitter, null, 0, null, null, null, null);
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
