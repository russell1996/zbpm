package com.zorrodev.bpm.engine.event;

import com.zorrodev.bpm.contract.dto.event.DomainEventType;
import com.zorrodev.bpm.engine.entity.DomainEventEntity;
import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.repository.DomainEventRepository;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
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
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DomainEventEmitterTest {

    @Mock private DomainEventRepository domainEventRepository;
    @Mock private OutboxRepository outboxRepository;
    @Mock private ProcessDefinitionRepository processDefinitionRepository;
    @Mock private tools.jackson.databind.ObjectMapper objectMapper;

    @InjectMocks
    private DomainEventEmitter emitter;

    @Test
    void emitUserTaskCreated_includesAssigneeAndCandidateGroupsInData() throws Exception {
        UUID piId = UUID.randomUUID();
        UUID pdId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();

        doReturn("{}").when(objectMapper).writeValueAsString(any());

        emitter.emitUserTaskCreated(piId, pdId, "userTask1", activityId, "ivanov", "managers,admins");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<DomainEventEntity> eventCaptor = ArgumentCaptor.forClass(DomainEventEntity.class);
        verify(domainEventRepository).save(eventCaptor.capture());
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
        verify(domainEventRepository).save(eventCaptor.capture());
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
        verify(domainEventRepository).save(eventCaptor.capture());
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
        verify(domainEventRepository).save(eventCaptor.capture());
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
        verify(domainEventRepository).save(eventCaptor.capture());
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
}
