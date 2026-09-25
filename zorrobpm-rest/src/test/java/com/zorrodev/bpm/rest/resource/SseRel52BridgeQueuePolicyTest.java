package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.security.Principal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-REL-52, A4 (механическая половина): declare bridge-очереди несёт
 * x-max-length + x-overflow. Тот же ArgumentCaptor-паттерн, что
 * JobQueueDeclarerTest (REL-45): ловим Queue, ушедший в declareQueue, и
 * проверяем КОНКРЕТНЫЕ аргументы (P-67), не «declare был».
 *
 * <p>Брокерная половина (брокер реально держит лимит и роняет head) —
 * rabbit-прогон SseRel52BridgeQueueIT через ci/run-rabbit-tests.sh.
 * POF-мутация: убрать queueArgs из startBridgeNow — этот тест КРАСНЫЙ.
 */
@ExtendWith(MockitoExtension.class)
class SseRel52BridgeQueuePolicyTest {

    @Mock
    private RabbitAdmin rabbitAdmin;
    @Mock
    private EventAuthzResolver eventAuthzResolver;

    @Test
    void bridgeDeclare_carriesMaxLengthAndDropHead() {
        when(eventAuthzResolver.readableRuntimePdIds(any(), any())).thenReturn(null);
        SseEventStreamService svc = new SseEventStreamService(
            org.mockito.Mockito.mock(com.zorrodev.bpm.engine.service.EventQueryService.class),
            eventAuthzResolver, rabbitAdmin, new ObjectMapper(), null, null);

        // Мост стартует при первой регистрации (фоновый starter — ждём
        // declare вместо сна: Awaitility на мок-взаимодействие).
        Principal admin = new Principal.UserPrincipal(UUID.randomUUID(), "admin", "SUPER_ADMIN");
        String clientId = svc.registerClient(new SseEmitter(0L), admin, null, null, null);
        try {
            ArgumentCaptor<Queue> captor = ArgumentCaptor.forClass(Queue.class);
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10))
                .untilAsserted(() -> verify(rabbitAdmin, atLeastOnce()).declareQueue(captor.capture()));
            List<Queue> bridges = captor.getAllValues().stream()
                .filter(q -> q.getName().startsWith("zorrobpm.sse-bridge."))
                .toList();
            assertThat(bridges).as("bridge queue declared").isNotEmpty();
            assertThat(bridges).allSatisfy(q -> {
                assertThat(q.isExclusive()).as("bridge queue exclusive").isTrue();
                assertThat(q.isAutoDelete()).as("bridge queue auto-delete").isTrue();
            });
            assertThat(bridges.get(0).getArguments())
                .as("bridge queue carries the WO-REL-52 overflow policy")
                .containsEntry("x-max-length", 10_000)
                .containsEntry("x-overflow", "drop-head");
        } finally {
            svc.removeClient(clientId);
        }
    }
}
