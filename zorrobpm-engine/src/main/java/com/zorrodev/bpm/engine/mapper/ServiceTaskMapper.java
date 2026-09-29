package com.zorrodev.bpm.engine.mapper;

import com.zorrodev.bpm.contract.model.ServiceTask;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ServiceTaskEntity;
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
public class ServiceTaskMapper {

    private final BpmnService bpmnService;
    private final ActivityRepository activityRepository;

    public ServiceTask toDTO(ServiceTaskEntity entity) {
        return toDTO(entity, activityRepository.findById(entity.getId()).orElse(null));
    }

    /**
     * WO-PERF-2 (D-01): batch-loads all referenced activities (for lifecycle status) in ONE
     * query instead of one findById per row. {@code bpmnService.getProcessDefinitionModelById}
     * is left per-row — it is served from BpmnServiceImpl's in-memory cache, not a DB hit, once
     * warm.
     */
    public List<ServiceTask> toDTOs(List<ServiceTaskEntity> entities) {
        Map<UUID, ActivityEntity> activities = loadActivities(entities);
        return entities.stream()
            .map(e -> toDTO(e, activities.get(e.getId())))
            .toList();
    }

    private Map<UUID, ActivityEntity> loadActivities(List<ServiceTaskEntity> entities) {
        List<UUID> ids = entities.stream().map(ServiceTaskEntity::getId).distinct().toList();
        return activityRepository.findAllById(ids).stream()
            .collect(Collectors.toMap(ActivityEntity::getId, Function.identity()));
    }

    private ServiceTask toDTO(ServiceTaskEntity entity, ActivityEntity activity) {
        BpmnElementModel element = bpmnService.getProcessDefinitionModelById(entity.getProcessDefinitionId()).getElement(entity.getBpmnElementId());
        ServiceTask task = new ServiceTask();
        task.setId(entity.getId());
        task.setName(element.getName());
        task.setCode(entity.getBpmnElementId());
        task.setProcessInstanceId(entity.getProcessInstanceId());
        task.setProcessDefinitionId(entity.getProcessDefinitionId());
        task.setCreatedAt(entity.getCreatedAt());
        task.setCompletedAt(entity.getCompletedAt());
        // the task id is the activity id: surface the authoritative lifecycle status (CANCELLED/ERROR
        // are not reflected on the service_tasks row, which is why a cancelled task looked "active")
        if (activity != null) {
            task.setStatus(activity.getStatus() == null ? null : activity.getStatus().name());
        }
        if (element.getExtensions() != null && element.getExtensions().getServiceTaskExtension() != null) {
            String job = element.getExtensions().getServiceTaskExtension().getJob();
            task.setJob(job);
        }
        return task;
    }
}
