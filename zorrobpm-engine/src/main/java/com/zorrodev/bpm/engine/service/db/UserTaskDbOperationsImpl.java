package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.event.DomainEventEmitter;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * WO-DEBT-1i: домен UserTasks — реализация.
 * Перенесено 1:1 из DBServiceImpl (5 методов).
 */
@Service
@RequiredArgsConstructor
public class UserTaskDbOperationsImpl implements UserTaskDbOperations {

    private final UserTaskRepository userTaskRepository;
    private final ActivityRepository activityRepository;
    private final ProcessInstanceRepository processInstanceRepository;
    private final DomainEventEmitter domainEventEmitter;

    @Override
    public void createUserTask(UUID activityId, String assignee, String candidateGroups, String formKey, String formId, String bindingType, String dueDate, String followUpDate, Integer priority) {
        ActivityEntity activity = activityRepository.findById(activityId).orElseThrow();
        UserTaskEntity entity = new UserTaskEntity();
        entity.setId(activity.getId());
        entity.setBpmnElementId(activity.getBpmnElementId());
        entity.setProcessInstanceId(activity.getProcessInstanceId());
        entity.setCreatedAt(activity.getCreatedAt());
        entity.setAssignee(assignee);
        entity.setCandidateGroups(candidateGroups);
        entity.setFormKey(formKey);
        entity.setFormId(formId);
        entity.setBindingType(bindingType);
        entity.setDueDate(dueDate);
        entity.setFollowUpDate(followUpDate);
        entity.setPriority(priority);

        ProcessInstanceEntity pi = processInstanceRepository.findById(activity.getProcessInstanceId()).orElseThrow();
        entity.setProcessDefinitionId(pi.getProcessDefinitionId());

        userTaskRepository.save(entity);
        domainEventEmitter.emitUserTaskCreated(activity.getProcessInstanceId(), pi.getProcessDefinitionId(), activity.getBpmnElementId(), activityId, assignee, candidateGroups);
    }

    // WO-C8-21r2: the round-1 phase API (startCreatingPhase/finishUserTaskCreation, the
    // index accessors and the requireCreated guards) is GONE with the marker row: no
    // user_tasks row exists until the task is really created, so there is nothing to
    // guard — a missing row orElseThrows by itself.

    @Override
    public void completeUserTask(UUID userTaskId) {
        UserTaskEntity ut = userTaskRepository.findById(userTaskId).orElseThrow();
        userTaskRepository.setCompletedAt(userTaskId, Instant.now());
        domainEventEmitter.emitUserTaskCompleted(ut.getProcessInstanceId(), ut.getProcessDefinitionId(), ut.getBpmnElementId(), userTaskId, ut.getAssignee());
    }

    @Override
    public void claimUserTask(UUID taskId, String assignee) {
        UserTaskEntity ut = userTaskRepository.findById(taskId).orElseThrow();
        if (ut.getCompletedAt() != null) {
            throw new IllegalStateException("User task is already completed");
        }
        int updated = userTaskRepository.claimAssignee(taskId, assignee);
        if (updated == 0) {
            throw new IllegalStateException("User task is already assigned");
        }
        domainEventEmitter.emitUserTaskAssigned(ut.getProcessInstanceId(), ut.getProcessDefinitionId(), ut.getBpmnElementId(), taskId, assignee);
    }

    @Override
    public void unclaimUserTask(UUID taskId) {
        UserTaskEntity ut = userTaskRepository.findById(taskId).orElseThrow();
        if (ut.getCompletedAt() != null) {
            throw new IllegalStateException("User task is already completed");
        }
        userTaskRepository.setAssignee(taskId, null);
        domainEventEmitter.emitUserTaskUnassigned(ut.getProcessInstanceId(), ut.getProcessDefinitionId(), ut.getBpmnElementId(), taskId);
    }

    @Override
    public void assignUserTask(UUID taskId, String assignee) {
        UserTaskEntity ut = userTaskRepository.findById(taskId).orElseThrow();
        if (ut.getCompletedAt() != null) {
            throw new IllegalStateException("User task is already completed");
        }
        userTaskRepository.setAssignee(taskId, assignee);
        domainEventEmitter.emitUserTaskAssigned(ut.getProcessInstanceId(), ut.getProcessDefinitionId(), ut.getBpmnElementId(), taskId, assignee);
    }
}
