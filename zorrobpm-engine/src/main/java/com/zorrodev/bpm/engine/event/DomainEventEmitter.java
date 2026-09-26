package com.zorrodev.bpm.engine.event;

import com.zorrodev.bpm.contract.dto.event.DomainEventType;
import com.zorrodev.bpm.engine.entity.DomainEventEntity;
import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.repository.DomainEventRepository;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.tracing.TracingSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
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
    private final TracingSupport tracing;

    // WO-PERF-1 N3: immutable process definitions → cache pdId→pdKey to avoid DB hit per event (bounded)
    private final Cache<UUID, String> pdKeyCache = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterAccess(Duration.ofMinutes(10))
        .build();

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

        // WO-REL-8a: sanitize data values to String — guarantees Jackson serialization never fails.
        // All emit* methods already produce String values, but this is the structural safety net.
        Map<String, Object> safeData = sanitizeData(data);

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
        event.setData(safeData);
        DomainEventEntity persisted = domainEventRepository.saveAndFlush(event);

        // WO-PERF-1 N3: resolve pdKey from cache (immutable process definitions, bounded)
        String processDefinitionKey = null;
        if (processDefinitionId != null) {
            processDefinitionKey = pdKeyCache.get(processDefinitionId, id ->
                processDefinitionRepository.findById(id)
                    .map(ProcessDefinitionEntity::getKey)
                    .orElse(null));
        }

        // Write to outbox for async delivery (reuse existing OutboxEntry pattern)
        // WO-REL-37 (F11): единый типизированный envelope — сериализация ПОСЛЕ save,
        // из СОХРАНЁННОЙ сущности: sequence (DB-generated IDENTITY) уже известен.
        // Ключ "id" (не "eventId") + sequence + version — та же форма, что REST
        // (EventQueryService.toEnvelope) и фронт (EventEnvelope: id+sequence).
        // Прод-потребителей старого ключа "eventId" ноль (проверено grep) — замена безопасна.
        try {
            Map<String, Object> envelope = new HashMap<>();
            envelope.put("id", eventId.toString());
            envelope.put("sequence", persisted.getSequence());
            // WO-REL-38: позиция курсора (null на момент emit — строка только
            // что вставлена, джоб назначит позже; live-мост ждёт тик, catchup
            // читает только назначенные).
            envelope.put("feedPosition", persisted.getFeedPosition());
            envelope.put("type", eventType.getValue());
            envelope.put("version", persisted.getVersion());
            envelope.put("occurredAt", occurredAt.toString());
            envelope.put("processInstanceId", processInstanceId != null ? processInstanceId.toString() : null);
            envelope.put("processDefinitionId", processDefinitionId != null ? processDefinitionId.toString() : null);
            envelope.put("processDefinitionKey", processDefinitionKey);
            envelope.put("elementId", elementId);
            envelope.put("data", safeData);

            OutboxEntry outboxEntry = new OutboxEntry();
            outboxEntry.setId(UUID.randomUUID());
            // WO-REL-12 R-01: producer knows the type — no payload guessing downstream
            outboxEntry.setKind(com.zorrodev.bpm.engine.entity.OutboxKind.DOMAIN_EVENT);
            outboxEntry.setPayload(objectMapper.writeValueAsString(envelope));
            outboxEntry.setCreatedAt(occurredAt);
            outboxEntry.setPublished(false);
            // WO-OBS-8: same trace_parent discipline as the service-task path.
            outboxEntry.setTraceParent(tracing.captureTraceParent());
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

    public void emitUserTaskCreated(UUID processInstanceId, UUID processDefinitionId, String elementId,
                                     UUID activityId, String assignee, String candidateGroups) {
        Map<String, Object> data = new HashMap<>();
        data.put("activityId", activityId.toString());
        if (assignee != null) data.put("assignee", assignee);
        if (candidateGroups != null) data.put("candidateGroups", candidateGroups);
        emit(DomainEventType.USER_TASK_CREATED, processInstanceId, processDefinitionId, elementId, data);
    }

    public void emitUserTaskCompleted(UUID processInstanceId, UUID processDefinitionId, String elementId,
                                       UUID activityId, String assignee) {
        Map<String, Object> data = new HashMap<>();
        data.put("activityId", activityId.toString());
        if (assignee != null) data.put("assignee", assignee);
        emit(DomainEventType.USER_TASK_COMPLETED, processInstanceId, processDefinitionId, elementId, data);
    }

    public void emitServiceTaskCreated(UUID processInstanceId, UUID processDefinitionId, String elementId, UUID activityId, String job) {
        Map<String, Object> data = new HashMap<>();
        data.put("activityId", activityId.toString());
        if (job != null) data.put("job", job);
        emit(DomainEventType.SERVICE_TASK_CREATED, processInstanceId, processDefinitionId, elementId, data);
    }

    public void emitActivityCompleted(UUID processInstanceId, UUID processDefinitionId, String elementId, String job) {
        Map<String, Object> data = new HashMap<>();
        if (job != null) data.put("job", job);
        emit(DomainEventType.ACTIVITY_COMPLETED, processInstanceId, processDefinitionId, elementId, data);
    }

    public void emitIncidentRaised(UUID processInstanceId, UUID processDefinitionId, String elementId, UUID incidentId, String message, String job) {
        Map<String, Object> data = new HashMap<>();
        data.put("incidentId", incidentId.toString());
        data.put("message", message);
        if (job != null) data.put("job", job);
        emit(DomainEventType.INCIDENT_RAISED, processInstanceId, processDefinitionId, elementId, data);
    }

    public void emitIncidentResolved(UUID processInstanceId, UUID processDefinitionId, String elementId, UUID incidentId, String job) {
        Map<String, Object> data = new HashMap<>();
        data.put("incidentId", incidentId.toString());
        if (job != null) data.put("job", job);
        emit(DomainEventType.INCIDENT_RESOLVED, processInstanceId, processDefinitionId, elementId, data);
    }

    public void emitUserTaskAssigned(UUID processInstanceId, UUID processDefinitionId, String elementId,
                                      UUID activityId, String assignee) {
        emit(DomainEventType.USER_TASK_ASSIGNED, processInstanceId, processDefinitionId, elementId,
            Map.of("activityId", activityId.toString(), "assignee", assignee));
    }

    public void emitUserTaskUnassigned(UUID processInstanceId, UUID processDefinitionId, String elementId,
                                        UUID activityId) {
        emit(DomainEventType.USER_TASK_UNASSIGNED, processInstanceId, processDefinitionId, elementId,
            Map.of("activityId", activityId.toString()));
    }

    /**
     * WO-REL-8a: convert all data values to String for guaranteed Jackson serialization.
     * Null values are preserved. This makes the catch block in emit() unreachable
     * for any data that passes through this method.
     */
    static Map<String, Object> sanitizeData(Map<String, Object> data) {
        if (data == null || data.isEmpty()) return Map.of();
        Map<String, Object> safe = new HashMap<>(data.size());
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            Object val = entry.getValue();
            safe.put(entry.getKey(), val != null ? val.toString() : null);
        }
        return safe;
    }
}
