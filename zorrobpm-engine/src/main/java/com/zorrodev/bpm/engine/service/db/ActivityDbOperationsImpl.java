package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnFlowModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.ServiceTaskEntity;
import com.zorrodev.bpm.engine.event.DomainEventEmitter;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * WO-DEBT-1n: домен Activities — реализация.
 * Перенесено 1:1 из DBServiceImpl (12 методов).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityDbOperationsImpl implements ActivityDbOperations {

    private final ActivityRepository activityRepository;
    private final ProcessInstanceRepository processInstanceRepository;
    private final ServiceTaskRepository serviceTaskRepository;
    private final DomainEventEmitter domainEventEmitter;

    @Override
    public UUID createActivity(UUID processInstanceId, UUID token, BpmnElementModel element) {
        UUID id = UUID.randomUUID();
        ActivityEntity entity = new ActivityEntity();
        entity.setId(id);
        entity.setProcessInstanceId(processInstanceId);
        entity.setCreatedAt(Instant.now());
        entity.setStatus(ActivityStatus.CREATED);
        entity.setType(element.getType());
        entity.setBpmnElementId(element.getId());
        entity.setToken(token);
        activityRepository.saveAndFlush(entity);
        return id;
    }

    @Override
    public UUID createActivity(UUID processInstanceId, UUID token, BpmnFlowModel element) {
        UUID id = UUID.randomUUID();
        ActivityEntity entity = new ActivityEntity();
        entity.setId(id);
        entity.setProcessInstanceId(processInstanceId);
        entity.setCreatedAt(Instant.now());
        entity.setStatus(ActivityStatus.CREATED);
        entity.setType(BpmnElementType.SEQUENCE_FLOW);
        entity.setBpmnElementId(element.getFlowId());
        entity.setToken(token);
        activityRepository.saveAndFlush(entity);
        return id;
    }

    @Override
    public void completeActivity(UUID activityId) {
        ActivityEntity activity = activityRepository.findById(activityId).orElseThrow();
        activityRepository.setStatusAndCompletedAt(activityId, ActivityStatus.COMPLETED, Instant.now());
        ProcessInstanceEntity pi = processInstanceRepository.findById(activity.getProcessInstanceId()).orElseThrow();
        // WO-EVT-9: service tasks carry their stable job id in the event data; other element types keep data empty.
        String job = serviceTaskRepository.findById(activityId).map(ServiceTaskEntity::getJob).orElse(null);
        domainEventEmitter.emitActivityCompleted(activity.getProcessInstanceId(), pi.getProcessDefinitionId(), activity.getBpmnElementId(), job);
    }

    @Override
    public void errorActivity(UUID activityId) {
        // WO-ENG-23: ERROR is a parked state, not a completion: leave completedAt unset.
        // Conditional: only an ACTIVE row (CREATED/IN_PROGRESS) may be parked. Flipping a
        // terminal row (COMPLETED/CANCELLED/ERROR) would corrupt history — e.g. a past loop
        // visit's COMPLETED row on a heap-ordered mis-pick. 0 updated rows = refuse, loudly.
        int updated = activityRepository.setStatusAndCompletedAtIfStatusIn(
            activityId, ActivityStatus.ERROR, null,
            List.of(ActivityStatus.CREATED, ActivityStatus.IN_PROGRESS));
        if (updated == 0) {
            log.warn("errorActivity({}): row is not active (already terminal?) — refusing to flip it to ERROR", activityId);
        }
    }

    @Override
    public void setPendingCreatingListenerIndex(UUID activityId, Integer index) {
        ActivityEntity entity = activityRepository.findById(activityId).orElseThrow();
        entity.setPendingCreatingListenerIndex(index);
        activityRepository.save(entity);
    }

    @Override
    public Integer getPendingCreatingListenerIndex(UUID activityId) {
        return activityRepository.findById(activityId).orElseThrow().getPendingCreatingListenerIndex();
    }

    @Override
    public void setCreatingListenerRetriesRemaining(UUID activityId, Integer remaining) {
        ActivityEntity entity = activityRepository.findById(activityId).orElseThrow();
        entity.setCreatingListenerRetriesRemaining(remaining);
        activityRepository.save(entity);
    }

    @Override
    public Integer getCreatingListenerRetriesRemaining(UUID activityId) {
        return activityRepository.findById(activityId).orElseThrow().getCreatingListenerRetriesRemaining();
    }

    @Override
    public void setPendingCompletingListenerIndex(UUID activityId, Integer index) {
        ActivityEntity entity = activityRepository.findById(activityId).orElseThrow();
        entity.setPendingCompletingListenerIndex(index);
        activityRepository.save(entity);
    }

    // WO-C8-28: assigning/updating/canceling phase accessors — mechanical mirror of
    // the creating/completing pairs above (load → set → save / load → get).

    @Override
    public void setPendingAssigningListenerIndex(UUID activityId, Integer index) {
        ActivityEntity entity = activityRepository.findById(activityId).orElseThrow();
        entity.setPendingAssigningListenerIndex(index);
        activityRepository.save(entity);
    }

    @Override
    public Integer getPendingAssigningListenerIndex(UUID activityId) {
        return activityRepository.findById(activityId).orElseThrow().getPendingAssigningListenerIndex();
    }

    @Override
    public void setAssigningListenerRetriesRemaining(UUID activityId, Integer remaining) {
        ActivityEntity entity = activityRepository.findById(activityId).orElseThrow();
        entity.setAssigningListenerRetriesRemaining(remaining);
        activityRepository.save(entity);
    }

    @Override
    public Integer getAssigningListenerRetriesRemaining(UUID activityId) {
        return activityRepository.findById(activityId).orElseThrow().getAssigningListenerRetriesRemaining();
    }

    @Override
    public void setPendingAssignee(UUID activityId, String assignee) {
        ActivityEntity entity = activityRepository.findById(activityId).orElseThrow();
        entity.setPendingAssignee(assignee);
        activityRepository.save(entity);
    }

    @Override
    public String getPendingAssignee(UUID activityId) {
        return activityRepository.findById(activityId).orElseThrow().getPendingAssignee();
    }

    @Override
    public void setPendingUpdatingListenerIndex(UUID activityId, Integer index) {
        ActivityEntity entity = activityRepository.findById(activityId).orElseThrow();
        entity.setPendingUpdatingListenerIndex(index);
        activityRepository.save(entity);
    }

    @Override
    public Integer getPendingUpdatingListenerIndex(UUID activityId) {
        return activityRepository.findById(activityId).orElseThrow().getPendingUpdatingListenerIndex();
    }

    @Override
    public void setUpdatingListenerRetriesRemaining(UUID activityId, Integer remaining) {
        ActivityEntity entity = activityRepository.findById(activityId).orElseThrow();
        entity.setUpdatingListenerRetriesRemaining(remaining);
        activityRepository.save(entity);
    }

    @Override
    public Integer getUpdatingListenerRetriesRemaining(UUID activityId) {
        return activityRepository.findById(activityId).orElseThrow().getUpdatingListenerRetriesRemaining();
    }

    @Override
    public void setPendingCancelingListenerIndex(UUID activityId, Integer index) {
        ActivityEntity entity = activityRepository.findById(activityId).orElseThrow();
        entity.setPendingCancelingListenerIndex(index);
        activityRepository.save(entity);
    }

    @Override
    public Integer getPendingCancelingListenerIndex(UUID activityId) {
        return activityRepository.findById(activityId).orElseThrow().getPendingCancelingListenerIndex();
    }

    @Override
    public void setCancelingListenerRetriesRemaining(UUID activityId, Integer remaining) {
        ActivityEntity entity = activityRepository.findById(activityId).orElseThrow();
        entity.setCancelingListenerRetriesRemaining(remaining);
        activityRepository.save(entity);
    }

    @Override
    public Integer getCancelingListenerRetriesRemaining(UUID activityId) {
        return activityRepository.findById(activityId).orElseThrow().getCancelingListenerRetriesRemaining();
    }

    @Override
    public void setPendingCancelBoundaryElementId(UUID activityId, String boundaryElementId) {
        ActivityEntity entity = activityRepository.findById(activityId).orElseThrow();
        entity.setPendingCancelBoundaryElementId(boundaryElementId);
        activityRepository.save(entity);
    }

    @Override
    public String getPendingCancelBoundaryElementId(UUID activityId) {
        return activityRepository.findById(activityId).orElseThrow().getPendingCancelBoundaryElementId();
    }

    @Override
    public boolean hasOpenCancelingListenerPhaseOnToken(UUID tokenId) {
        return !activityRepository.findByTokenAndPendingCancelingListenerIndexIsNotNull(tokenId).isEmpty();
    }

    @Override
    public boolean hasOpenCancelingListenerPhaseInInstance(UUID processInstanceId) {
        return !activityRepository
            .findByProcessInstanceIdAndPendingCancelingListenerIndexIsNotNull(processInstanceId).isEmpty();
    }

    @Override
    public Integer getPendingCompletingListenerIndex(UUID activityId) {
        return activityRepository.findById(activityId).orElseThrow().getPendingCompletingListenerIndex();
    }

    @Override
    public void setCompletingListenerRetriesRemaining(UUID activityId, Integer remaining) {
        ActivityEntity entity = activityRepository.findById(activityId).orElseThrow();
        entity.setCompletingListenerRetriesRemaining(remaining);
        activityRepository.save(entity);
    }

    @Override
    public Integer getCompletingListenerRetriesRemaining(UUID activityId) {
        return activityRepository.findById(activityId).orElseThrow().getCompletingListenerRetriesRemaining();
    }

    @Override
    public void cancelActivity(UUID activityId) {
        activityRepository.setStatusAndCompletedAt(activityId, ActivityStatus.CANCELLED, Instant.now());
    }

    @Override
    public void cancelActiveActivities(UUID processInstanceId) {
        List<ActivityEntity> active = activityRepository.findByProcessInstanceIdAndStatusIn(
            processInstanceId, List.of(ActivityStatus.CREATED, ActivityStatus.IN_PROGRESS));
        for (ActivityEntity activity : active) {
            activityRepository.setStatusAndCompletedAt(activity.getId(), ActivityStatus.CANCELLED, Instant.now());
        }
    }

    @Override
    public void cancelActiveActivitiesForToken(UUID tokenId) {
        List<ActivityEntity> active = activityRepository.findByTokenAndStatusIn(
            tokenId, List.of(ActivityStatus.CREATED, ActivityStatus.IN_PROGRESS));
        for (ActivityEntity activity : active) {
            activityRepository.setStatusAndCompletedAt(activity.getId(), ActivityStatus.CANCELLED, Instant.now());
        }
    }

    @Override
    public List<Activity> getActiveActivities(UUID processInstanceId) {
        // WO-REL-31 CR-3: deterministic id-ASC order (see repo method javadoc)
        return activityRepository.findByProcessInstanceIdAndStatusInOrderByIdAsc(
                processInstanceId, List.of(ActivityStatus.CREATED, ActivityStatus.IN_PROGRESS)).stream()
            .map(this::getActivity)
            .toList();
    }

    @Override
    public boolean hasActiveActivityOnTokenAndElement(UUID tokenId, String bpmnElementId) {
        return !activityRepository.findByTokenAndBpmnElementIdAndStatusIn(
                tokenId, bpmnElementId, List.of(ActivityStatus.CREATED, ActivityStatus.IN_PROGRESS))
            .isEmpty();
    }

    @Override
    public List<Activity> getCompletedActivities(UUID processInstanceId) {
        return activityRepository.findByProcessInstanceIdAndStatusIn(
                processInstanceId, List.of(ActivityStatus.COMPLETED)).stream()
            .map(this::getActivity)
            .toList();
    }

    @Override
    public Activity getActivity(UUID activityId) {
        ActivityEntity activityEntity = activityRepository.findById(activityId).orElseThrow();
        return getActivity(activityEntity);
    }

    @Override
    public Activity getActivityForUpdate(UUID activityId) {
        ActivityEntity activityEntity = activityRepository.findByIdForUpdate(activityId).orElseThrow();
        return getActivity(activityEntity);
    }

    @Override
    public List<Activity> getActivitiesByTokenAndBpmnElementId(UUID token, String bpmnElementId) {
        return activityRepository.findByTokenAndBpmnElementId(token, bpmnElementId).stream()
            .map(this::getActivity)
            .toList();
    }

    private Activity getActivity(ActivityEntity activityEntity) {
        Activity activity = new Activity();
        activity.setId(activityEntity.getId());
        activity.setProcessInstanceId(activityEntity.getProcessInstanceId());
        activity.setBpmnElementId(activityEntity.getBpmnElementId());
        activity.setCreatedAt(activityEntity.getCreatedAt());
        activity.setCompletedAt(activityEntity.getCompletedAt());
        activity.setStatus(activityEntity.getStatus());
        activity.setType(activityEntity.getType());
        activity.setToken(activityEntity.getToken());
        return activity;
    }
}
