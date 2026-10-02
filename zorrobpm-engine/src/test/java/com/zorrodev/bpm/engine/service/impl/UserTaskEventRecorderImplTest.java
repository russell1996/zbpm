package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.UserTaskCandidateEntity;
import com.zorrodev.bpm.engine.entity.UserTaskCandidateType;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.entity.UserTaskEventOutboxEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.UserTaskCandidateRepository;
import com.zorrodev.bpm.engine.repository.UserTaskEventOutboxRepository;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.event.UserTaskEventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserTaskEventRecorderImplTest {

    private final UserTaskEventOutboxRepository outboxRepository = mock(UserTaskEventOutboxRepository.class);
    private final UserTaskCandidateRepository candidateRepository = mock(UserTaskCandidateRepository.class);
    private final ProcessDefinitionRepository processDefinitionRepository = mock(ProcessDefinitionRepository.class);
    private final BpmnService bpmnService = mock(BpmnService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private UserTaskEventRecorderImpl recorder(String enabled) {
        return new UserTaskEventRecorderImpl(enabled, outboxRepository, candidateRepository, processDefinitionRepository,
            bpmnService, objectMapper);
    }

    @Test
    void disabled_writesNothing() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        recorder("false").record(new UserTaskEntity(), UserTaskEventType.CREATED, Instant.now());

        verifyNoInteractions(outboxRepository, candidateRepository, processDefinitionRepository, bpmnService);
    }

    @Test
    void outsideATransaction_fails() {
        UserTaskEntity task = new UserTaskEntity();

        assertThatThrownBy(() -> recorder("true").record(task, UserTaskEventType.CREATED, Instant.now()))
            .isInstanceOf(IllegalTransactionStateException.class);
        verifyNoInteractions(outboxRepository);
    }

    @Test
    void created_writesTheTaskAndProcessToThePayload() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        UUID processDefinitionId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-02T10:15:30Z");
        UserTaskEntity task = new UserTaskEntity();
        task.setId(UUID.randomUUID());
        task.setBpmnElementId("approve");
        task.setFormKey("approve-form");
        task.setProcessInstanceId(UUID.randomUUID());
        task.setProcessDefinitionId(processDefinitionId);
        task.setCreatedAt(createdAt);

        BpmnProcessDefinitionModel model = new BpmnProcessDefinitionModel();
        BpmnElementModel element = new BpmnElementModel();
        element.setId("approve");
        element.setType(BpmnElementType.USER_TASK);
        element.setName("Согласовать");
        model.addElement(element);
        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(model);
        ProcessDefinitionEntity pd = new ProcessDefinitionEntity();
        pd.setKey("order");
        pd.setVersion(2);
        when(processDefinitionRepository.findById(processDefinitionId)).thenReturn(Optional.of(pd));
        UserTaskCandidateEntity group = new UserTaskCandidateEntity();
        group.setCandidateType(UserTaskCandidateType.GROUP);
        group.setCandidateValue("managers");
        when(candidateRepository.findByTaskId(task.getId())).thenReturn(List.of(group));

        recorder("true").record(task, UserTaskEventType.CREATED, createdAt);

        ArgumentCaptor<UserTaskEventOutboxEntity> captor = ArgumentCaptor.forClass(UserTaskEventOutboxEntity.class);
        verify(outboxRepository).save(captor.capture());
        UserTaskEventOutboxEntity row = captor.getValue();
        assertThat(row.getEventType()).isEqualTo("CREATED");
        assertThat(row.getUserTaskId()).isEqualTo(task.getId());
        assertThat(row.getCreatedAt()).isEqualTo(createdAt);

        JsonNode json = objectMapper.readTree(row.getPayload());
        assertThat(json.get("eventId").asString()).isEqualTo(row.getEventId().toString());
        assertThat(json.get("type").asString()).isEqualTo("CREATED");
        assertThat(json.get("bpmnElementId").asString()).isEqualTo("approve");
        assertThat(json.get("name").asString()).isEqualTo("Согласовать");
        assertThat(json.get("formKey").asString()).isEqualTo("approve-form");
        assertThat(json.get("candidateGroups").get(0).asString()).isEqualTo("managers");
        assertThat(json.get("candidateUsers")).isEmpty();
        assertThat(json.get("assignee").isNull()).isTrue();
        assertThat(json.get("processDefinitionKey").asString()).isEqualTo("order");
        assertThat(json.get("processDefinitionVersion").asInt()).isEqualTo(2);
        assertThat(json.get("occurredAt").asString()).isEqualTo("2026-10-02T10:15:30Z");
    }

    @Test
    void assigned_doesNotReportTheCompletionOfALaterState() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        UserTaskEntity task = new UserTaskEntity();
        task.setId(UUID.randomUUID());
        task.setProcessDefinitionId(UUID.randomUUID());
        task.setAssignee("111");
        task.setCompletedAt(Instant.now());

        recorder("true").record(task, UserTaskEventType.ASSIGNED, Instant.now());

        ArgumentCaptor<UserTaskEventOutboxEntity> captor = ArgumentCaptor.forClass(UserTaskEventOutboxEntity.class);
        verify(outboxRepository).save(captor.capture());
        JsonNode json = objectMapper.readTree(captor.getValue().getPayload());
        assertThat(json.get("assignee").asString()).isEqualTo("111");
        assertThat(json.get("completedAt").isNull()).isTrue();
        assertThat(json.get("name").isNull()).isTrue();
    }
}
