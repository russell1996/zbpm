package com.zorrodev.bpm.engine.tracing;

import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.entity.OutboxKind;
import com.zorrodev.bpm.engine.scheduler.OutboxBatchProcessor;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ServiceTaskEnqueued;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-OBS-8 criterion 1 (engine half): TWO service-task outbox entries enqueued under
 * ONE trace produce Spring events carrying traceparents with the SAME trace id —
 * i.e. the {@code trace_parent} column + per-entry child span survive the
 * {@code @Scheduled} gap instead of forking one trace per entry.
 *
 * <p>Real SDK + in-memory exporter (never noop — see {@link TracingSupportTest}).
 * The broker half of the chain (AMQP headers) is covered in
 * {@code zorrobpm-rabbitmq} (send) + {@code JobCompletionListener} (worker) tests
 * and the {@code @Tag("rabbit")} live-broker loop.
 */
@ExtendWith(MockitoExtension.class)
class OutboxTracePropagationTest {

    @Mock private OutboxRepository outboxRepository;
    @Mock private ApplicationEventPublisher publisher;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private InMemorySpanExporter exporter;
    private TracingSupport tracing;
    private OutboxBatchProcessor processor;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();
        tracing = new TracingSupport(OpenTelemetrySdk.builder()
            .setTracerProvider(provider)
            .build());
        processor = new OutboxBatchProcessor(outboxRepository, publisher, objectMapper,
            new com.zorrodev.bpm.engine.metrics.BpmMetrics(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
            mock(com.zorrodev.bpm.engine.event.DomainEventEmitter.class), tracing);
        org.springframework.test.util.ReflectionTestUtils.setField(processor, "batchSize", 100);
        org.springframework.test.util.ReflectionTestUtils.setField(processor, "maxRetries", 5);
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        exporter.reset();
    }

    private OutboxEntry serviceTaskEntry(UUID processInstanceId, String traceParent) throws Exception {
        JobDetailModel detail = new JobDetailModel();
        detail.setServiceTaskId(UUID.randomUUID());
        detail.setProcessInstanceId(processInstanceId);
        detail.setJob("probe");
        OutboxEntry e = new OutboxEntry();
        e.setId(UUID.randomUUID());
        e.setKind(OutboxKind.SERVICE_TASK);
        e.setPayload(objectMapper.writeValueAsString(detail));
        e.setTraceParent(traceParent);
        e.setCreatedAt(Instant.now());
        e.setPublished(false);
        return e;
    }

    @Test
    void twoEntriesUnderOneTrace_eventsShareSingleTraceId() throws Exception {
        UUID pi = UUID.randomUUID();
        String enqueueTraceParent;
        try (TracingSupport.TraceScope enqueue = tracing.openChildSpan(null, "test.enqueue", pi.toString(), null)) {
            enqueueTraceParent = tracing.captureTraceParent();
            assertThat(enqueueTraceParent).startsWith("00-");
        }
        String enqueueTraceId = enqueueTraceParent.split("-")[1];

        // Both rows were written under the same enqueue trace (as writeOutboxEntry does).
        OutboxEntry first = serviceTaskEntry(pi, enqueueTraceParent);
        OutboxEntry second = serviceTaskEntry(pi, enqueueTraceParent);
        when(outboxRepository.findPendingBatch(anyInt())).thenReturn(List.of(first, second));

        processor.processBatch();

        ArgumentCaptor<ServiceTaskEnqueued> captor = ArgumentCaptor.forClass(ServiceTaskEnqueued.class);
        verify(publisher, org.mockito.Mockito.times(2)).publishEvent(captor.capture());
        List<ServiceTaskEnqueued> events = captor.getAllValues();
        assertThat(events).hasSize(2);
        for (ServiceTaskEnqueued event : events) {
            assertThat(event.getTraceParent())
                .as("per-entry child traceparent must be present (not a silent untraced hop)")
                .isNotNull()
                .startsWith("00-" + enqueueTraceId + "-");
        }
        // The per-entry spans (outbox.process) are children of the SAME enqueue trace.
        assertThat(exporter.getFinishedSpanItems())
            .filteredOn(span -> span.getName().equals("outbox.process"))
            .hasSize(2)
            .allSatisfy(span -> assertThat(span.getSpanContext().getTraceId()).isEqualTo(enqueueTraceId));
    }

    @Test
    void untracedEntry_eventCarriesPerEntryTrace_stillPublished() throws Exception {
        // The per-entry span always exists (even for a stored-null parent): the event
        // carries the CHILD traceparent, whose trace id is fresh. The point that must
        // hold: untraced rows still publish (no NPE/skip), with a VALID traceparent.
        OutboxEntry entry = serviceTaskEntry(UUID.randomUUID(), null);
        when(outboxRepository.findPendingBatch(anyInt())).thenReturn(List.of(entry));

        processor.processBatch();

        ArgumentCaptor<ServiceTaskEnqueued> captor = ArgumentCaptor.forClass(ServiceTaskEnqueued.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getTraceParent())
            .isNotNull()
            .startsWith("00-");
        assertThat(exporter.getFinishedSpanItems())
            .filteredOn(span -> span.getName().equals("outbox.process"))
            .hasSize(1);
    }
}
