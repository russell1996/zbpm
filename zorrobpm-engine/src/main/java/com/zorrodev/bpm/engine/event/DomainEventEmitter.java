package com.zorrodev.bpm.engine.event;

import com.zorrodev.bpm.contract.dto.event.DomainEventType;
import com.zorrodev.bpm.engine.entity.DomainEventEntity;
import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.repository.DomainEventRepository;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Emits domain events to the events table + outbox in the same transaction (ADR-7, WO-EVT-1).
 * At-least-once delivery: consumer must dedupe by event id/sequence.
 * All context is passed as parameters — no dependency on DBService (avoids circular dep).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DomainEventEmitter {

    private final DomainEventRepository domainEventRepository;
    private final OutboxRepository outboxRepository;
    private final ProcessDefinitionRepository processDefinitionRepository;
    private final tools.jackson.databind.ObjectMapper objectMapper;

    /**
     * Emits a domain event: writes to events table + outbox entry in the same transaction.
     * Must be called within an active transaction (the caller's transaction).
     */
    @Transactional
    public void emit(DomainEventType eventType, UUID processInstanceId, UUID processDefinitionId,
                     String elementId, Map<String, Object> data) {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.now();

        // Determine ownerScope (processDefinitionId for authz filtering per ADR-2)
        String ownerScope = processDefinitionId != null ? processDefinitionId.toString() : null;

        // Write to events table (append-only, monotonic sequence)
        DomainEventEntity event = new DomainEventEntity();
        event.setId(eventId);
        event.setType(eventType.getValue());
        event.setVersion(1);
        event.setOccurredAt(occurredAt);
        event.setProcessDefinitionId(processDefinitionId);
        event.setProcessInstanceId(processInstanceId);
        event.setElementId(elementId);
        event.setOwnerScope(ownerScope);
        event.setData(data != null ? data : Map.of());
        domainEventRepository.save(event);

        // Resolve processDefinitionKey from the definition entity (for routing key, WO-EVT-7)
        String[] pdKeyHolder = {null};
        if (processDefinitionId != null) {
            processDefinitionRepository.findById(processDefinitionId)
                .ifPresent(pd -> pdKeyHolder[0] = pd.getKey());
        }
        String processDefinitionKey = pdKeyHolder[0];

        // Write to outbox for async delivery (reuse existing OutboxEntry pattern)
        try {
            Map<String, Object> envelope = new HashMap<>();
            envelope.put("eventId", eventId.toString());
            envelope.put("type", eventType.getValue());
            envelope.put("occurredAt", occurredAt.toString());
            envelope.put("processInstanceId", processInstanceId != null ? processInstanceId.toString() : null);
            envelope.put("processDefinitionId", processDefinitionId != null ? processDefinitionId.toString() : null);
            envelope.put("processDefinitionKey", processDefinitionKey);
            envelope.put("elementId", elementId);
            envelope.put("data", data != null ? data : Map.of());

            OutboxEntry outboxEntry = new OutboxEntry();
            outboxEntry.setId(UUID.randomUUID());
            outboxEntry.setPayload(objectMapper.writeValueAsString(envelope));
            outboxEntry.setCreatedAt(occurredAt);
            outboxEntry.setPublished(false);
            outboxRepository.save(outboxEntry);
        } catch (Exception e) {
            log.error("Failed to serialize event for outbox: {}", eventId, e);
            // Event is still in events table — outbox write failure is non-fatal
        }

        log.debug("Emitted domain event: type={}, id={}, processInstanceId={}", eventType.getValue(), eventId, processInstanceId);
    }

    public void emitProcessInstanceStarted(UUID processInstanceId, UUID processDefinitionId) {
        emit(DomainEventType.PROCESS_INSTANCE_STARTED, processInstanceId, processDefinitionId, null, Map.of());
    }

    public void emitProcessInstanceCompleted(UUID processInstanceId, UUID processDefinitionId) {
        emit(DomainEventType.PROCESS_INSTANCE_COMPLETED, processInstanceId, processDefinitionId, null, Map.of());
    }

    public void emitProcessInstanceCancelled(UUID processInstanceId, UUID processDefinitionId) {
        emit(DomainEventType.PROCESS_INSTANCE_CANCELLED, processInstanceId, processDefinitionId, null, Map.of());
    }

    public void emitActivityCompleted(UUID processInstanceId, UUID processDefinitionId, String elementId) {
        emit(DomainEventType.ACTIVITY_COMPLETED, processInstanceId, processDefinitionId, elementId, Map.of());
    }

    public void emitUserTaskCreated(UUID processInstanceId, UUID processDefinitionId, String elementId, UUID activityId) {
        emit(DomainEventType.USER_TASK_CREATED, processInstanceId, processDefinitionId, elementId,
            Map.of("activityId", activityId.toString()));
    }

    public void emitUserTaskCompleted(UUID processInstanceId, UUID processDefinitionId, String elementId, UUID activityId) {
        emit(DomainEventType.USER_TASK_COMPLETED, processInstanceId, processDefinitionId, elementId,
            Map.of("activityId", activityId.toString()));
    }

    public void emitServiceTaskCreated(UUID processInstanceId, UUID processDefinitionId, String elementId, UUID activityId) {
        emit(DomainEventType.SERVICE_TASK_CREATED, processInstanceId, processDefinitionId, elementId,
            Map.of("activityId", activityId.toString()));
    }

    public void emitIncidentRaised(UUID processInstanceId, UUID processDefinitionId, String elementId, UUID incidentId, String message) {
        emit(DomainEventType.INCIDENT_RAISED, processInstanceId, processDefinitionId, elementId,
            Map.of("incidentId", incidentId.toString(), "message", message));
    }

    public void emitIncidentResolved(UUID processInstanceId, UUID processDefinitionId, String elementId, UUID incidentId) {
        emit(DomainEventType.INCIDENT_RESOLVED, processInstanceId, processDefinitionId, elementId,
            Map.of("incidentId", incidentId.toString()));
    }
}
