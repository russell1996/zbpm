package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.entity.OutboxKind;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.exchange.DomainEventPublished;
import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ProcessVariable;
import com.zorrodev.bpm.exchange.ServiceTaskEnqueued;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * WO-REL-12 (R-01): routing is decided by the explicit {@link OutboxKind} column, never by
 * substring guessing over the payload.
 *
 * Criterion 1 (POF): a service-task payload whose serialized JSON literally contains the
 * substrings "type" and "eventId" (e.g. a business variable named eventId + ProcessVariable
 * "type" field) must be published as a ServiceTaskEnqueued job — on the old code
 * isDomainEvent(payload) returned true and the entry went to the domain-event exchange, so
 * the worker never got the job and the process hung silently.
 *
 * Criterion 2 (regression): a DOMAIN_EVENT entry is always published as DomainEventPublished,
 * regardless of payload content.
 */
@ExtendWith(MockitoExtension.class)
class OutboxBatchProcessorKindTest {

    @Mock private OutboxRepository outboxRepository;
    @Mock private ApplicationEventPublisher publisher;

    /** Real Jackson ObjectMapper (same as production wiring), not a mock. */
    private final ObjectMapper objectMapper = new ObjectMapper();
    private OutboxBatchProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new OutboxBatchProcessor(outboxRepository, publisher, objectMapper, new com.zorrodev.bpm.engine.metrics.BpmMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), mock(com.zorrodev.bpm.engine.event.DomainEventEmitter.class), com.zorrodev.bpm.engine.tracing.TracingSupport.noop());
        org.springframework.test.util.ReflectionTestUtils.setField(processor, "batchSize", 100);
        org.springframework.test.util.ReflectionTestUtils.setField(processor, "maxRetries", 5);
    }

    private OutboxEntry entry(OutboxKind kind, String payload) {
        OutboxEntry e = new OutboxEntry();
        e.setId(UUID.randomUUID());
        e.setKind(kind);
        e.setPayload(payload);
        e.setCreatedAt(Instant.now());
        e.setPublished(false);
        return e;
    }

    /**
     * Serialized JobDetailModel whose JSON contains BOTH "type" and "eventId" as TOP-LEVEL
     * keys — exactly what the old substring heuristic (payload.containsKey("type") &&
     * payload.containsKey("eventId")) matched, while the payload is still a perfectly valid
     * service-task job (Jackson ignores the extra keys). This is the payload that tricked the
     * old heuristic: the job went to the domain-event exchange and the process hung silently.
     */
    private String serviceTaskPayloadWithTypeAndEventIdSubstrings() throws Exception {
        JobDetailModel detail = new JobDetailModel();
        detail.setServiceTaskId(UUID.randomUUID());
        detail.setJob("send-email");
        ProcessVariable v = new ProcessVariable();
        v.setName("eventId");
        v.setValue("42");
        v.setType("STRING");
        detail.setVariables(Map.of("eventId", v));
        // Serialize, add the trap keys on the TOP level of the JSON object, re-serialize.
        Map<String, Object> asMap = objectMapper.readValue(objectMapper.writeValueAsString(detail), Map.class);
        asMap.put("type", "SomeDomainEventType");
        asMap.put("eventId", UUID.randomUUID().toString());
        String json = objectMapper.writeValueAsString(asMap);
        // Sanity: the trap payload really is (a) a valid JobDetailModel and (b) contains both
        // top-level substrings the old code looked for.
        JobDetailModel parsedBack = objectMapper.readValue(json, JobDetailModel.class);
        assertThat(parsedBack.getJob()).isEqualTo("send-email");
        assertThat(json).contains("\"type\"");
        assertThat(json).contains("\"eventId\"");
        return json;
    }

    @Test
    void criterion1_serviceTaskPayloadWithTypeAndEventIdSubstrings_publishedAsServiceTaskNotDomainEvent()
            throws Exception {
        OutboxEntry entry = entry(OutboxKind.SERVICE_TASK, serviceTaskPayloadWithTypeAndEventIdSubstrings());
        when(outboxRepository.findPendingBatch(100)).thenReturn(List.of(entry));

        processor.processBatch();

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue())
            .as("kind=SERVICE_TASK must be routed to the job queue even if the payload contains " +
                "\"type\"/\"eventId\" substrings (WO-REL-12 R-01)")
            .isInstanceOf(ServiceTaskEnqueued.class);
        verify(publisher, never()).publishEvent(any(DomainEventPublished.class));
    }

    @Test
    void criterion2_domainEventKind_publishedAsDomainEvent_regardlessOfPayloadContent() {
        // Payload that does NOT even look like a domain envelope ("type"/"eventId" absent) —
        // the explicit kind must win, not payload guessing.
        OutboxEntry entry = entry(OutboxKind.DOMAIN_EVENT, "{\"foo\":\"bar\"}");
        when(outboxRepository.findPendingBatch(100)).thenReturn(List.of(entry));

        processor.processBatch();

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue())
            .as("kind=DOMAIN_EVENT must always be routed to the domain-event exchange (WO-REL-12 R-01)")
            .isInstanceOf(DomainEventPublished.class);
        verify(publisher, never()).publishEvent(any(ServiceTaskEnqueued.class));
    }

    @Test
    void criterion2_domainEventKind_payloadWithSubstrings_publishedAsDomainEvent() throws Exception {
        OutboxEntry entry = entry(OutboxKind.DOMAIN_EVENT, serviceTaskPayloadWithTypeAndEventIdSubstrings());
        when(outboxRepository.findPendingBatch(100)).thenReturn(List.of(entry));

        processor.processBatch();

        verify(publisher, times(1)).publishEvent(any(DomainEventPublished.class));
        verify(publisher, never()).publishEvent(any(ServiceTaskEnqueued.class));
    }

    @Test
    void eventCarriesOutboxId_asCorrelationForBrokerAck() throws Exception {
        OutboxEntry entry = entry(OutboxKind.SERVICE_TASK, "{\"serviceTaskId\":\"" + UUID.randomUUID() + "\"}");
        when(outboxRepository.findPendingBatch(100)).thenReturn(List.of(entry));

        processor.processBatch();

        ArgumentCaptor<ServiceTaskEnqueued> captor = ArgumentCaptor.forClass(ServiceTaskEnqueued.class);
        verify(publisher).publishEvent(captor.capture());
        // WO-REL-12 R-06: stable messageId = outbox id, carried through to CorrelationData
        assertThat(captor.getValue().getOutboxId()).isEqualTo(entry.getId().toString());
    }
}
