package com.zorrodev.bpm.engine.mapper;

import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.service.BpmnService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class UserTaskMapper {

    /** Документный дефолт приоритета (BPMN без явного приоритета). */
    public static final int DEFAULT_PRIORITY = 50;

    private final BpmnService bpmnService;
    private final ActivityRepository activityRepository;

    public UserTask toDTO(UserTaskEntity entity) {
        return toDTO(entity, activityRepository.findById(entity.getId()).orElse(null));
    }

    /**
     * WO-PERF-2 (D-01): batch-loads all referenced activities (for lifecycle status) in ONE
     * query instead of one findById per row. {@code bpmnService.getProcessDefinitionModelById}
     * is left per-row — it is served from BpmnServiceImpl's in-memory cache, not a DB hit, once
     * warm.
     */
    public List<UserTask> toDTOs(List<UserTaskEntity> entities) {
        Map<UUID, ActivityEntity> activities = loadActivities(entities);
        return entities.stream()
            .map(e -> toDTO(e, activities.get(e.getId())))
            .toList();
    }

    private Map<UUID, ActivityEntity> loadActivities(List<UserTaskEntity> entities) {
        List<UUID> ids = entities.stream().map(UserTaskEntity::getId).distinct().toList();
        return activityRepository.findAllById(ids).stream()
            .collect(Collectors.toMap(ActivityEntity::getId, Function.identity()));
    }

    private UserTask toDTO(UserTaskEntity entity, ActivityEntity activity) {
        BpmnElementModel element = bpmnService.getProcessDefinitionModelById(entity.getProcessDefinitionId()).getElement(entity.getBpmnElementId());
        UserTask dto = new UserTask();
        dto.setId(entity.getId());
        dto.setName(element.getName());
        dto.setProcessInstanceId(entity.getProcessInstanceId());
        dto.setProcessDefinitionId(entity.getProcessDefinitionId());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setCompletedAt(entity.getCompletedAt());
        dto.setCode(entity.getBpmnElementId());
        dto.setFormKey(entity.getFormKey());
        dto.setDueDate(entity.getDueDate());
        dto.setFollowUpDate(entity.getFollowUpDate());
        // WO-C8-30: effective priority, pre-WO null rows read as the docs default 50.
        dto.setPriority(entity.getPriority() != null ? entity.getPriority() : DEFAULT_PRIORITY);
        // task id == activity id: expose the authoritative lifecycle status for the UI
        if (activity != null) {
            dto.setStatus(activity.getStatus() == null ? null : activity.getStatus().name());
        }
        return dto;
    }
}
