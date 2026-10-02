package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.entity.UserTaskCandidateEntity;
import com.zorrodev.bpm.engine.entity.UserTaskCandidateType;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.entity.UserTaskEventOutboxEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.UserTaskCandidateRepository;
import com.zorrodev.bpm.engine.repository.UserTaskEventOutboxRepository;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.UserTaskEventRecorder;
import com.zorrodev.bpm.event.UserTaskEventType;
import com.zorrodev.bpm.event.UserTaskLifecycleEvent;
import com.zorrodev.bpm.exchange.UserTaskEvents;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class UserTaskEventRecorderImpl implements UserTaskEventRecorder {

    private final boolean enabled;
    private final UserTaskEventOutboxRepository outboxRepository;
    private final UserTaskCandidateRepository candidateRepository;
    private final ProcessDefinitionRepository processDefinitionRepository;
    private final BpmnService bpmnService;
    private final ObjectMapper objectMapper;

    public UserTaskEventRecorderImpl(@Value("${" + UserTaskEvents.ENABLED_PROPERTY + ":false}") String enabled,
                                     UserTaskEventOutboxRepository outboxRepository,
                                     UserTaskCandidateRepository candidateRepository,
                                     ProcessDefinitionRepository processDefinitionRepository,
                                     BpmnService bpmnService,
                                     ObjectMapper objectMapper) {
        this.enabled = UserTaskEvents.enabled(enabled);
        this.outboxRepository = outboxRepository;
        this.candidateRepository = candidateRepository;
        this.processDefinitionRepository = processDefinitionRepository;
        this.bpmnService = bpmnService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void record(UserTaskEntity task, UserTaskEventType type, Instant occurredAt) {
        if (!enabled) {
            return;
        }
        // Outside a transaction the event would be saved apart from the change it reports
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException("A user task event must be recorded in the transaction of the change");
        }
        UserTaskLifecycleEvent event = toEvent(task, type, occurredAt);

        UserTaskEventOutboxEntity row = new UserTaskEventOutboxEntity();
        row.setEventId(event.getEventId());
        row.setEventType(type.name());
        row.setUserTaskId(task.getId());
        row.setPayload(objectMapper.writeValueAsString(event));
        row.setCreatedAt(occurredAt);
        outboxRepository.save(row);
    }

    UserTaskLifecycleEvent toEvent(UserTaskEntity task, UserTaskEventType type, Instant occurredAt) {
        UserTaskLifecycleEvent event = new UserTaskLifecycleEvent();
        event.setEventId(UUID.randomUUID());
        event.setType(type);
        event.setOccurredAt(occurredAt);

        event.setUserTaskId(task.getId());
        event.setBpmnElementId(task.getBpmnElementId());
        event.setName(elementName(task));
        event.setFormKey(task.getFormKey());
        event.setAssignee(task.getAssignee());
        List<UserTaskCandidateEntity> candidates = candidateRepository.findByTaskId(task.getId());
        event.setCandidateUsers(candidateValues(candidates, UserTaskCandidateType.USER));
        event.setCandidateGroups(candidateValues(candidates, UserTaskCandidateType.GROUP));
        event.setCreatedAt(task.getCreatedAt());
        event.setCompletedAt(type == UserTaskEventType.COMPLETED ? task.getCompletedAt() : null);
        event.setCanceledAt(type == UserTaskEventType.CANCELED ? task.getCanceledAt() : null);
        event.setLoopIndex(task.getLoopIndex());
        event.setLoopTotal(task.getLoopTotal());

        event.setProcessInstanceId(task.getProcessInstanceId());
        event.setProcessDefinitionId(task.getProcessDefinitionId());
        processDefinitionRepository.findById(task.getProcessDefinitionId()).ifPresent(pd -> {
            event.setProcessDefinitionKey(pd.getKey());
            event.setProcessDefinitionVersion(pd.getVersion());
        });
        return event;
    }

    private String elementName(UserTaskEntity task) {
        return Optional.ofNullable(bpmnService.getProcessDefinitionModelById(task.getProcessDefinitionId()))
            .map(model -> model.getElement(task.getBpmnElementId()))
            .map(BpmnElementModel::getName)
            .orElse(null);
    }

    private static List<String> candidateValues(List<UserTaskCandidateEntity> candidates, UserTaskCandidateType type) {
        return candidates.stream()
            .filter(c -> c.getCandidateType() == type)
            .map(UserTaskCandidateEntity::getCandidateValue)
            .toList();
    }
}
