package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.entity.OutboxKind;
import com.zorrodev.bpm.engine.metrics.BpmMetrics;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.engine.tracing.TracingSupport;
import com.zorrodev.bpm.exchange.OutboxDeliveryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-REL-66 (B): оба пути карантина режут петлю одинаково —
 * недоставленное уведомление о карантине дропается с учётом
 * (markPublished + метрика), а НЕ карантинится рекурсивно.
 * Обычные незамаршрутизируемые события behaviour не меняют (контроль).
 */
@ExtendWith(MockitoExtension.class)
class OutboxQuarantineLoopGuardTest {

    @Mock private OutboxRepository outboxRepository;
    @Mock private ApplicationEventPublisher publisher;
    @Mock private BpmMetrics bpmMetrics;
    @Mock private com.zorrodev.bpm.engine.event.DomainEventEmitter domainEventEmitter;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OutboxBatchProcessor processor;
    private OutboxDeliveryResultListener listener;

    private String envelope(String type) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
            "id", UUID.randomUUID().toString(),
            "sequence", 1,
            "type", type,
            "data", Map.of("outboxId", UUID.randomUUID().toString())));
    }

    private OutboxEntry notificationEntry() throws Exception {
        OutboxEntry e = new OutboxEntry();
        e.setId(UUID.randomUUID());
        e.setKind(OutboxKind.DOMAIN_EVENT);
        e.setPayload(envelope("outbox.quarantined"));
        e.setCreatedAt(Instant.now());
        e.setPublished(false);
        e.setAttempts(4);
        return e;
    }

    @BeforeEach
    void setUp() {
        processor = new OutboxBatchProcessor(outboxRepository, publisher, objectMapper,
            bpmMetrics, domainEventEmitter, TracingSupport.noop());
        ReflectionTestUtils.setField(processor, "batchSize", 100);
        ReflectionTestUtils.setField(processor, "maxRetries", 5);
        listener = new OutboxDeliveryResultListener(
            outboxRepository, bpmMetrics, domainEventEmitter, objectMapper);
        ReflectionTestUtils.setField(listener, "maxRetries", 5);
    }

    @Test
    void processorPath_quarantineNotification_droppedNotQuarantined() throws Exception {
        OutboxEntry entry = notificationEntry();
        when(outboxRepository.findPendingBatch(100)).thenReturn(List.of(entry));
        // Публикация падает (транспорт) на последней попытке.
        org.mockito.Mockito.doThrow(new RuntimeException("NO_ROUTE"))
            .when(publisher).publishEvent(any());
        // WO-REL-69 П.1: attempts=4 = ceiling → условный UPDATE не применяется,
        // карантинный путь attempts не трогает.
        when(outboxRepository.incrementAttempts(eq(entry.getId()), any(), eq(4)))
            .thenReturn(0);
        when(outboxRepository.findAttemptsById(entry.getId())).thenReturn(4);

        processor.processBatch();

        verify(outboxRepository).markPublished(entry.getId());
        verify(outboxRepository, never()).markFailed(any());
        verify(domainEventEmitter, never()).emit(any(), any(), any(), any(), any());
        verify(bpmMetrics).domainEventUnroutable("outbox.quarantined");
    }

    @Test
    void deliveryResultPath_quarantineNotification_droppedNotQuarantined() throws Exception {
        OutboxEntry entry = notificationEntry();
        when(outboxRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        // WO-REL-69 П.1: attempts=4 = ceiling → UPDATE не применяется.
        when(outboxRepository.incrementAttempts(eq(entry.getId()), any(), eq(4)))
            .thenReturn(0);
        when(outboxRepository.findAttemptsById(entry.getId())).thenReturn(4);

        listener.on(new OutboxDeliveryResult(
            entry.getId().toString(), false, "unroutable: 312 NO_ROUTE"));

        verify(outboxRepository).markPublished(entry.getId());
        verify(outboxRepository, never()).markFailed(any());
        verify(domainEventEmitter, never()).emit(any(), any(), any(), any(), any());
        verify(bpmMetrics).domainEventUnroutable("outbox.quarantined");
    }

    @Test
    void processorPath_ordinaryUnroutableEvent_stillQuarantinedWithNotify() throws Exception {
        OutboxEntry entry = new OutboxEntry();
        entry.setId(UUID.randomUUID());
        entry.setKind(OutboxKind.DOMAIN_EVENT);
        entry.setPayload(envelope("rel66.unroutable"));
        entry.setCreatedAt(Instant.now());
        entry.setPublished(false);
        entry.setAttempts(4);
        when(outboxRepository.findPendingBatch(100)).thenReturn(List.of(entry));
        org.mockito.Mockito.doThrow(new RuntimeException("NO_ROUTE"))
            .when(publisher).publishEvent(any());
        // WO-REL-69 П.1: attempts=4 = ceiling → UPDATE не применяется,
        // карантинный путь attempts не трогает.
        when(outboxRepository.incrementAttempts(eq(entry.getId()), any(), eq(4)))
            .thenReturn(0);
        when(outboxRepository.findAttemptsById(entry.getId())).thenReturn(4);
        when(outboxRepository.markFailed(entry.getId())).thenReturn(1);

        processor.processBatch();

        verify(outboxRepository).markFailed(entry.getId());
        verify(domainEventEmitter).emit(
            eq(com.zorrodev.bpm.contract.dto.event.DomainEventType.OUTBOX_QUARANTINED),
            any(), any(), any(), any());
        verify(outboxRepository, never()).markPublished(any());
    }

    @Test
    void deliveryResultPath_ordinaryUnroutableEvent_stillQuarantinedWithNotify() throws Exception {
        OutboxEntry entry = new OutboxEntry();
        entry.setId(UUID.randomUUID());
        entry.setKind(OutboxKind.DOMAIN_EVENT);
        entry.setPayload(envelope("rel66.unroutable"));
        entry.setCreatedAt(Instant.now());
        entry.setPublished(false);
        entry.setAttempts(4);
        entry.setStatus(com.zorrodev.bpm.engine.entity.OutboxStatus.PENDING);
        when(outboxRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        // WO-REL-69 П.1: attempts=4 = ceiling → UPDATE не применяется.
        when(outboxRepository.incrementAttempts(eq(entry.getId()), any(), eq(4)))
            .thenReturn(0);
        when(outboxRepository.findAttemptsById(entry.getId())).thenReturn(4);
        when(outboxRepository.markFailed(entry.getId())).thenReturn(1);

        listener.on(new OutboxDeliveryResult(
            entry.getId().toString(), false, "unroutable: 312 NO_ROUTE"));

        verify(outboxRepository).markFailed(entry.getId());
        verify(domainEventEmitter).emit(
            eq(com.zorrodev.bpm.contract.dto.event.DomainEventType.OUTBOX_QUARANTINED),
            any(), any(), any(), any());
        verify(outboxRepository, never()).markPublished(any());
    }
}
