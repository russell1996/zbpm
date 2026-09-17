package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.event.DomainEventEmitter;
import com.zorrodev.bpm.engine.metrics.BpmMetrics;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * WO-PERF-8 (O1/O2): outbox gauges must be sampled periodically, not on every 2s
 * tick (two COUNT(*) per tick), and the default chunk must be 50, not 100.
 */
@ExtendWith(MockitoExtension.class)
class OutboxMetricsSamplingTest {

    @Mock private OutboxRepository outboxRepository;
    @Mock private ApplicationEventPublisher publisher;
    @Mock private BpmMetrics bpmMetrics;
    @Mock private DomainEventEmitter domainEventEmitter;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OutboxBatchProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new OutboxBatchProcessor(outboxRepository, publisher, objectMapper,
            bpmMetrics, domainEventEmitter);
        lenient().when(outboxRepository.findPendingBatch(anyInt())).thenReturn(List.of());
    }

    @Test
    void defaults_chunk50_sampleEvery30() {
        // `new`-constructed (no Spring injection): field initializers ARE the defaults.
        assertThat(ReflectionTestUtils.getField(processor, "batchSize")).isEqualTo(50);
        assertThat(ReflectionTestUtils.getField(processor, "metricsSampleEvery")).isEqualTo(30);
    }

    @Test
    void springDefaults_chunk50_sampleEvery30() throws Exception {
        // The @Value strings are the defaults for Spring-managed instances.
        String batchSizeValue = OutboxBatchProcessor.class.getDeclaredField("batchSize")
            .getAnnotation(org.springframework.beans.factory.annotation.Value.class).value();
        String sampleValue = OutboxBatchProcessor.class.getDeclaredField("metricsSampleEvery")
            .getAnnotation(org.springframework.beans.factory.annotation.Value.class).value();
        assertThat(batchSizeValue).isEqualTo("${zorrobpm.outbox.batch-size:50}");
        assertThat(sampleValue).isEqualTo("${zorrobpm.outbox.metrics-sample-every:30}");
    }

    @Test
    void firstTick_alwaysSamples() {
        ReflectionTestUtils.setField(processor, "metricsSampleEvery", 30);

        processor.processBatch();

        verify(outboxRepository, times(1)).countPending();
        verify(outboxRepository, times(1)).countQuarantined();
    }

    @Test
    void gauges_sampledEvery30thTick_notEveryTick() {
        ReflectionTestUtils.setField(processor, "metricsSampleEvery", 30);

        for (int i = 0; i < 30; i++) {
            processor.processBatch();
        }
        // Ticks 1..30: only tick 1 samples.
        verify(outboxRepository, times(1)).countPending();
        verify(outboxRepository, times(1)).countQuarantined();

        processor.processBatch();
        // Tick 31: second sample.
        verify(outboxRepository, times(2)).countPending();
        verify(outboxRepository, times(2)).countQuarantined();
    }

    @Test
    void sampleEveryZeroOrNegative_failOpen_samplesEveryTick() {
        ReflectionTestUtils.setField(processor, "metricsSampleEvery", 0);

        for (int i = 0; i < 3; i++) {
            processor.processBatch();
        }
        verify(outboxRepository, times(3)).countPending();
        verify(outboxRepository, times(3)).countQuarantined();
    }
}
