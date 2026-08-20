package com.zorrodev.bpm.engine.mapper;

import com.zorrodev.bpm.contract.dto.Incident;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
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
public class IncidentMapper {

    private final ActivityRepository activityRepository;
    private final ProcessInstanceRepository processInstanceRepository;
    private final ProcessDefinitionRepository processDefinitionRepository;
    private final BpmnService bpmnService;

    public Incident toDTO(IncidentEntity entity) {
        Incident pi = new Incident();
        pi.setId(entity.getId());
        pi.setActivityId(entity.getActivityId());
        pi.setMessage(entity.getMessage());
        pi.setCompletedAt(entity.getCompletedAt());
        pi.setCreatedAt(entity.getCreatedAt());
        return pi;
    }

    /**
     * WO-ACL-16: batch enrichment of a page of incidents. The chain incident → activity →
     * process instance → process definition is loaded in THREE queries total (one per level)
     * instead of one findById per incident row. {@code bpmnService.getProcessDefinitionModelById}
     * is left per-row — it is served from BpmnServiceImpl's in-memory cache, not a DB hit, once
     * warm. A deleted/unavailable definition or model must not break the list: enrichment then
     * simply leaves the new fields empty (criterion 3).
     */
    public List<Incident> enrich(List<Incident> dtos) {
        Map<UUID, ActivityEntity> activities = loadActivities(dtos);
        Map<UUID, ProcessInstanceEntity> instances = loadInstances(activities);
        Map<UUID, ProcessDefinitionEntity> definitions = loadDefinitions(instances);
        return dtos.stream()
            .map(dto -> enrich(dto, activities.get(dto.getActivityId()), instances, definitions))
            .toList();
    }

    private Incident enrich(Incident dto, ActivityEntity activity,
                            Map<UUID, ProcessInstanceEntity> instances,
                            Map<UUID, ProcessDefinitionEntity> definitions) {
        if (activity == null) {
            return dto;
        }
        dto.setBpmnElementId(activity.getBpmnElementId());
        ProcessInstanceEntity pi = instances.get(activity.getProcessInstanceId());
        if (pi == null) {
            return dto;
        }
        dto.setProcessInstanceId(pi.getId());
        ProcessDefinitionEntity pd = definitions.get(pi.getProcessDefinitionId());
        if (pd == null) {
            return dto;
        }
        dto.setProcessName(pd.getName());
        dto.setElementName(resolveElementName(pd, activity.getBpmnElementId()));
        return dto;
    }

    private String resolveElementName(ProcessDefinitionEntity pd, String bpmnElementId) {
        if (bpmnElementId == null) {
            return null;
        }
        try {
            BpmnElementModel element = bpmnService.getProcessDefinitionModelById(pd.getId()).getElement(bpmnElementId);
            return element == null ? null : element.getName();
        } catch (RuntimeException e) {
            // deleted/unavailable model (criterion 3): no element name, no 500
            return null;
        }
    }

    private Map<UUID, ActivityEntity> loadActivities(List<Incident> dtos) {
        List<UUID> ids = dtos.stream().map(Incident::getActivityId).filter(java.util.Objects::nonNull).distinct().toList();
        return activityRepository.findAllById(ids).stream()
            .collect(Collectors.toMap(ActivityEntity::getId, Function.identity()));
    }

    private Map<UUID, ProcessInstanceEntity> loadInstances(Map<UUID, ActivityEntity> activities) {
        List<UUID> ids = activities.values().stream()
            .map(ActivityEntity::getProcessInstanceId)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
        return processInstanceRepository.findAllById(ids).stream()
            .collect(Collectors.toMap(ProcessInstanceEntity::getId, Function.identity()));
    }

    private Map<UUID, ProcessDefinitionEntity> loadDefinitions(Map<UUID, ProcessInstanceEntity> instances) {
        List<UUID> ids = instances.values().stream()
            .map(ProcessInstanceEntity::getProcessDefinitionId)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
        return processDefinitionRepository.findAllById(ids).stream()
            .collect(Collectors.toMap(ProcessDefinitionEntity::getId, Function.identity()));
    }
}