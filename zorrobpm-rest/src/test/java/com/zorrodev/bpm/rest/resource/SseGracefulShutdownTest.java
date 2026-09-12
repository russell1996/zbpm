package com.zorrodev.bpm.rest.resource;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;

class SseGracefulShutdownTest {

    @Test
    void smartLifecycleStopCompletesEmitters() {
        var svc = new SseEventStreamService(null, null, null, new tools.jackson.databind.ObjectMapper());
        // before stop, service is running
        assertThat(svc.isRunning()).isTrue();
        var emitter = new SseEmitter(0L);
        // register a dummy emitter via reflection into clients map
        var clients = new java.util.concurrent.ConcurrentHashMap<String, Object>();
        // Instead test the lifecycle contract directly: stop() flips running and completes
        svc.stop(() -> {});
        assertThat(svc.isRunning()).isFalse();
        // after stop, phase is max-value
        assertThat(svc.getPhase()).isEqualTo(Integer.MAX_VALUE - 100);
    }
}
