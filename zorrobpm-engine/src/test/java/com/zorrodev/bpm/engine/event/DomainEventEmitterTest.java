package com.zorrodev.bpm.engine.event;

import com.zorrodev.bpm.contract.dto.event.DomainEventType;
import com.zorrodev.bpm.engine.entity.DomainEventEntity;
import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.repository.DomainEventRepository;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DomainEventEmitterTest {

    @Mock private DomainEventRepository domainEventRepository;
    @Mock private OutboxRepository outboxRepository;
    @Mock private ProcessDefinitionRepository processDefinitionRepository;
    @Mock private tools.jackson.databind.ObjectMapper objectMapper;
    @Mock private com.zorrodev.bpm.engine.tracing.TracingSupport tracing;

    @InjectMocks
    private DomainEventEmitter emitter;

    @BeforeEach
    void defaultSaveAndFlushReturnsArgument() {
        // WO-REL-37: прод зовёт saveAndFlush (sequence нужен сразу для envelope).
        // По умолчанию мок возвращал null -> NPE в emit; эмулируем JPA: возвращается
        // тот же entity (sequence подставляет БД — здесь null, тесты ниже sequence
        // не проверяют, кроме F11-теста со своим стабом).
        lenient().doAnswer(inv -> inv.getArgument(0)).when(domainEventRepository)
            .saveAndFlush(any(DomainEventEntity.class));
    }

    @Test
    void emitUserTaskCreated_includesAssigneeAndCandidateGroupsInData() throws Exception {
        UUID piId = UUID.randomUUID();
        UUID pdId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();

        doReturn("{}").when(objectMapper).writeValueAsString(any());

        emitter.emitUserTaskCreated(piId, pdId, "userTask1", activityId, "ivanov", "managers,admins");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<DomainEventEntity> eventCaptor = ArgumentCaptor.forClass(DomainEventEntity.class);
        verify(domainEventRepository).saveAndFlush(eventCaptor.capture());
        Map<String, Object> data = eventCaptor.getValue().getData();
        assertThat(data).containsEntry("activityId", activityId.toString());
        assertThat(data).containsEntry("assignee", "ivanov");
        assertThat(data).containsEntry("candidateGroups", "managers,admins");
    }

    @Test
    void emitUserTaskCreated_nullAssignee_omitsAssigneeFromData() throws Exception {
        UUID piId = UUID.randomUUID();
        UUID pdId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();

        doReturn("{}").when(objectMapper).writeValueAsString(any());

        emitter.emitUserTaskCreated(piId, pdId, "ut-pool", activityId, null, "managers");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<DomainEventEntity> eventCaptor = ArgumentCaptor.forClass(DomainEventEntity.class);
        verify(domainEventRepository).saveAndFlush(eventCaptor.capture());
        Map<String, Object> data = eventCaptor.getValue().getData();
        assertThat(data).containsEntry("activityId", activityId.toString());
        assertThat(data).containsEntry("candidateGroups", "managers");
        assertThat(data).doesNotContainKey("assignee");
    }

    @Test
    void emitUserTaskCompleted_includesAssigneeInData() throws Exception {
        UUID piId = UUID.randomUUID();
        UUID pdId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();

        doReturn("{}").when(objectMapper).writeValueAsString(any());

        emitter.emitUserTaskCompleted(piId, pdId, "userTask1", activityId, "petrov");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<DomainEventEntity> eventCaptor = ArgumentCaptor.forClass(DomainEventEntity.class);
        verify(domainEventRepository).saveAndFlush(eventCaptor.capture());
        Map<String, Object> data = eventCaptor.getValue().getData();
        assertThat(data).containsEntry("activityId", activityId.toString());
        assertThat(data).containsEntry("assignee", "petrov");
    }

    @Test
    void emitUserTaskCompleted_nullAssignee_omitsAssigneeFromData() throws Exception {
        UUID piId = UUID.randomUUID();
        UUID pdId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();

        doReturn("{}").when(objectMapper).writeValueAsString(any());

        emitter.emitUserTaskCompleted(piId, pdId, "userTask1", activityId, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<DomainEventEntity> eventCaptor = ArgumentCaptor.forClass(DomainEventEntity.class);
        verify(domainEventRepository).saveAndFlush(eventCaptor.capture());
        Map<String, Object> data = eventCaptor.getValue().getData();
        assertThat(data).containsEntry("activityId", activityId.toString());
        assertThat(data).doesNotContainKey("assignee");
    }

    /**
     * POF: Before enrichment, emitUserTaskCreated only accepted activityId.
     * Now it accepts assignee and candidateGroups, and they appear in data.
     * RED: without the new parameters, data would only have "activityId".
     * GREEN: with the new parameters, data has "activityId", "assignee", "candidateGroups".
     */
    @Test
    void pof_enrichment_dataContainsAssigneeAndCandidateGroups() throws Exception {
        UUID piId = UUID.randomUUID();
        UUID pdId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();

        doReturn("{}").when(objectMapper).writeValueAsString(any());

        emitter.emitUserTaskCreated(piId, pdId, "ut1", activityId, "ivanov", "managers");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<DomainEventEntity> eventCaptor = ArgumentCaptor.forClass(DomainEventEntity.class);
        verify(domainEventRepository).saveAndFlush(eventCaptor.capture());
        Map<String, Object> data = eventCaptor.getValue().getData();

        // POF GREEN: data now contains assignee and candidateGroups
        assertThat(data).hasSize(3);
        assertThat(data).containsEntry("activityId", activityId.toString());
        assertThat(data).containsEntry("assignee", "ivanov");
        assertThat(data).containsEntry("candidateGroups", "managers");

        // POF RED proof: if we only passed activityId (old behavior), data would be:
        // Map.of("activityId", activityId.toString()) — size=1, no assignee, no candidateGroups
        // The new signature ensures these fields are always present when provided.
    }

    // ==================== WO-REL-8a ====================

    /**
     * WO-REL-8a POF: non-serializable value in data → sanitizeData converts to String
     * → objectMapper.writeValueAsString succeeds (outbox entry created).
     * RED (without sanitize): non-serializable value stays → serialization can fail → outbox miss.
     * GREEN (with sanitize): all values are toString'd → serialization always succeeds.
     */
    @Test
    void pof_nonSerializableData_sanitizedForOutbox() throws Exception {
        UUID piId = UUID.randomUUID();
        UUID pdId = UUID.randomUUID();

        // Non-serializable object — would cause Jackson to fail
        Map<String, Object> data = Map.of("activityId", UUID.randomUUID().toString(), "weird", 42);

        doReturn("{}").when(objectMapper).writeValueAsString(any());

        emitter.emit(DomainEventType.ACTIVITY_COMPLETED, piId, pdId, "el1", data);

        // Verify event saved
        @SuppressWarnings("unchecked")
        ArgumentCaptor<DomainEventEntity> eventCaptor = ArgumentCaptor.forClass(DomainEventEntity.class);
        verify(domainEventRepository).saveAndFlush(eventCaptor.capture());

        // Verify outbox entry was created (serialization did NOT fail) with explicit kind (WO-REL-12 R-01)
        ArgumentCaptor<OutboxEntry> outboxCaptor = ArgumentCaptor.forClass(OutboxEntry.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getKind()).isEqualTo(com.zorrodev.bpm.engine.entity.OutboxKind.DOMAIN_EVENT);

        // Verify data was sanitized: Integer 42 → String "42"
        Map<String, Object> savedData = eventCaptor.getValue().getData();
        assertThat(savedData.get("weird")).isEqualTo("42");
        assertThat(savedData.get("weird")).isInstanceOf(String.class);
    }

    @Test
    void sanitizeData_nullValues_preserved() {
        java.util.HashMap<String, Object> input = new java.util.HashMap<>();
        input.put("a", "hello");
        input.put("b", null);
        Map<String, Object> result = DomainEventEmitter.sanitizeData(input);
        assertThat(result).containsEntry("a", "hello");
        assertThat(result.get("b")).isNull();
    }

    @Test
    void sanitizeData_emptyMap_returnsEmptyMap() {
        Map<String, Object> result = DomainEventEmitter.sanitizeData(null);
        assertThat(result).isEmpty();
    }

    @Test
    void sanitizeData_alreadyStrings_unchanged() {
        UUID id = UUID.randomUUID();
        Map<String, Object> input = Map.of("id", id, "name", "test");
        Map<String, Object> result = DomainEventEmitter.sanitizeData(input);
        assertThat(result.get("id")).isEqualTo(id.toString());
        assertThat(result.get("name")).isEqualTo("test");
    }

    // ==================== WO-REL-37 F11: sequence в envelope после save ====================

    /**
     * WO-REL-37 F11 POF: envelope обязан нести DB-generated sequence (IDENTITY при
     * save) — live SSE читает envelope.sequence как курсор, без него id: 0 и
     * reconnect всегда с Last-Event-ID: 0. RED (до фикса): envelope строился ДО
     * save из сырого event (sequence ещё null) и без ключа вообще.
     * GREEN (после): saveAndFlush → envelope из persisted (id+sequence+version).
     */
    @Test
    void emit_envelopeCarriesDbSequenceAfterSave() throws Exception {
        UUID piId = UUID.randomUUID();
        UUID pdId = UUID.randomUUID();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        doReturn("{}").when(objectMapper).writeValueAsString(payloadCaptor.capture());

        // saveAndFlush возвращает persisted с DB-generated sequence (как IDENTITY в проде)
        DomainEventEntity persisted = new DomainEventEntity();
        persisted.setSequence(987L);
        persisted.setVersion(3);
        doReturn(persisted).when(domainEventRepository).saveAndFlush(any(DomainEventEntity.class));

        emitter.emit(DomainEventType.PROCESS_INSTANCE_STARTED, piId, pdId, null, Map.of());

        verify(domainEventRepository).saveAndFlush(any(DomainEventEntity.class));
        Map<String, Object> envelope = payloadCaptor.getValue();
        assertThat(envelope.get("sequence")).isEqualTo(987L);
        assertThat(envelope.get("version")).isEqualTo(3);
        assertThat(envelope.get("id")).isNotNull();
        assertThat(envelope).doesNotContainKey("eventId");
        // WO-REL-38: курсор читает feedPosition, а не sequence (identity —
        // отладка/совместимость, не граница). persisted без позиции — envelope
        // честно несёт её отсутствие (джоб назначит позже).
        assertThat(envelope).containsKey("feedPosition");
        assertThat(envelope.get("feedPosition")).isNull();
    }

    /**
     * POF (G-N): two emits with same pdId → findById called ONLY ONCE (from cache).
     * RED (without cache): findById called twice.
     */
    @Test
    void emit_samePdId_findByIdCalledOnlyOnce() throws Exception {
        UUID piId = UUID.randomUUID();
        UUID pdId = UUID.randomUUID();
        com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity pd =
            new com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity();
        pd.setKey("myProcess");

        doReturn("{}").when(objectMapper).writeValueAsString(any());
        doReturn(java.util.Optional.of(pd)).when(processDefinitionRepository).findById(pdId);

        emitter.emit(DomainEventType.PROCESS_INSTANCE_STARTED, piId, pdId, null, Map.of());
        emitter.emit(DomainEventType.PROCESS_INSTANCE_COMPLETED, piId, pdId, null, Map.of());

        // findById should be called only once (second call hits cache)
        verify(processDefinitionRepository, org.mockito.Mockito.atMost(1)).findById(pdId);
    }
}
