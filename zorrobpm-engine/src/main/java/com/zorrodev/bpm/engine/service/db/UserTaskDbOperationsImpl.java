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
    public void createUserTask(UUID activityId, String assignee, String candidateGroups, String formKey, String formId, String dueDate, String followUpDate) {
        ActivityEntity activity = activityRepository.findById(activityId).orElseThrow();
        UserTaskEntity entity = new UserTaskEntity();
        entity.setId(activity.getId());
        entity.setBpmnElementId(activity.getBpmnElementId());
        entity.setProcessInstanceId(activity.getProcessInstanceId());
        entity.setCreatedAt(activity.getCreatedAt());
        fillCreationFields(entity, assignee, candidateGroups, formKey, formId, dueDate, followUpDate);

        ProcessInstanceEntity pi = processInstanceRepository.findById(activity.getProcessInstanceId()).orElseThrow();
        entity.setProcessDefinitionId(pi.getProcessDefinitionId());

        userTaskRepository.save(entity);
        domainEventEmitter.emitUserTaskCreated(activity.getProcessInstanceId(), pi.getProcessDefinitionId(), activity.getBpmnElementId(), activityId, assignee, candidateGroups);
    }

    /**
     * WO-C8-21: shared tail of both creation paths (immediate and phased) — resolved task
     * fields only, no phase/index/event logic, so the two paths cannot diverge.
     */
    private void fillCreationFields(UserTaskEntity entity, String assignee, String candidateGroups, String formKey, String formId, String dueDate, String followUpDate) {
        entity.setAssignee(assignee);
        entity.setCandidateGroups(candidateGroups);
        entity.setFormKey(formKey);
        entity.setFormId(formId);
        entity.setDueDate(dueDate);
        entity.setFollowUpDate(followUpDate);
    }

    @Override
    public void startCreatingPhase(UUID activityId) {
        ActivityEntity activity = activityRepository.findById(activityId).orElseThrow();
        UserTaskEntity entity = new UserTaskEntity();
        entity.setId(activity.getId());
        entity.setBpmnElementId(activity.getBpmnElementId());
        entity.setProcessInstanceId(activity.getProcessInstanceId());
        entity.setCreatedAt(activity.getCreatedAt());
        entity.setPendingCreatingListenerIndex(0);

        ProcessInstanceEntity pi = processInstanceRepository.findById(activity.getProcessInstanceId()).orElseThrow();
        entity.setProcessDefinitionId(pi.getProcessDefinitionId());

        userTaskRepository.save(entity);
        // Deliberately NO emitUserTaskCreated: the task does not exist yet — it becomes
        // visible/claimable only when finishUserTaskCreation clears the index.
    }

    @Override
    public void finishUserTaskCreation(UUID activityId, String assignee, String candidateGroups, String formKey, String formId, String dueDate, String followUpDate) {
        UserTaskEntity entity = userTaskRepository.findById(activityId).orElseThrow();
        fillCreationFields(entity, assignee, candidateGroups, formKey, formId, dueDate, followUpDate);
        entity.setPendingCreatingListenerIndex(null);
        userTaskRepository.save(entity);

        ProcessInstanceEntity pi = processInstanceRepository.findById(entity.getProcessInstanceId()).orElseThrow();
        domainEventEmitter.emitUserTaskCreated(entity.getProcessInstanceId(), pi.getProcessDefinitionId(), entity.getBpmnElementId(), activityId, assignee, candidateGroups);
    }

    @Override
    public void setPendingCreatingListenerIndex(UUID taskId, Integer index) {
        // Entity mutation + save (mirror of ServiceTaskDbOperationsImpl.setPendingListenerIndex),
        // NOT a bulk UPDATE: the enqueue in the same transaction re-reads the index via findById,
        // and a bulk update would leave the persistence-context copy stale (redispatched listener #0).
        UserTaskEntity entity = userTaskRepository.findById(taskId).orElseThrow();
        entity.setPendingCreatingListenerIndex(index);
        userTaskRepository.save(entity);
    }

    @Override
    public Integer getPendingCreatingListenerIndex(UUID taskId) {
        return userTaskRepository.findById(taskId).orElseThrow().getPendingCreatingListenerIndex();
    }

    /**
     * WO-C8-21: a task mid-creating-phase is not a task yet — it cannot be completed,
     * claimed or (re)assigned. Same exception type as the pre-existing guards.
     */
    private void requireCreated(UserTaskEntity ut) {
        if (ut.getPendingCreatingListenerIndex() != null) {
            throw new IllegalStateException("User task " + ut.getId() + " is not created yet (creating listener phase in flight)");
        }
    }

    @Override
    public void completeUserTask(UUID userTaskId) {
        UserTaskEntity ut = userTaskRepository.findById(userTaskId).orElseThrow();
        requireCreated(ut);
        userTaskRepository.setCompletedAt(userTaskId, Instant.now());
        domainEventEmitter.emitUserTaskCompleted(ut.getProcessInstanceId(), ut.getProcessDefinitionId(), ut.getBpmnElementId(), userTaskId, ut.getAssignee());
    }

    @Override
    public void claimUserTask(UUID taskId, String assignee) {
        UserTaskEntity ut = userTaskRepository.findById(taskId).orElseThrow();
        requireCreated(ut);
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
        requireCreated(ut);
        if (ut.getCompletedAt() != null) {
            throw new IllegalStateException("User task is already completed");
        }
        userTaskRepository.setAssignee(taskId, null);
        domainEventEmitter.emitUserTaskUnassigned(ut.getProcessInstanceId(), ut.getProcessDefinitionId(), ut.getBpmnElementId(), taskId);
    }

    @Override
    public void assignUserTask(UUID taskId, String assignee) {
        UserTaskEntity ut = userTaskRepository.findById(taskId).orElseThrow();
        requireCreated(ut);
        if (ut.getCompletedAt() != null) {
            throw new IllegalStateException("User task is already completed");
        }
        userTaskRepository.setAssignee(taskId, assignee);
        domainEventEmitter.emitUserTaskAssigned(ut.getProcessInstanceId(), ut.getProcessDefinitionId(), ut.getBpmnElementId(), taskId, assignee);
    }
}
