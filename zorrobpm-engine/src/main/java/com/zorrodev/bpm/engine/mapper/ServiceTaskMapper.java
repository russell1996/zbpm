package com.zorrodev.bpm.engine.mapper;

import com.zorrodev.bpm.contract.model.ServiceTask;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.entity.ServiceTaskEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.service.BpmnService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ServiceTaskMapper {

    private final BpmnService bpmnService;
    private final ActivityRepository activityRepository;

    public ServiceTask toDTO(ServiceTaskEntity entity) {
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
        activityRepository.findById(entity.getId())
            .ifPresent(a -> task.setStatus(a.getStatus() == null ? null : a.getStatus().name()));
        if (element.getExtensions() != null && element.getExtensions().getServiceTaskExtension() != null) {
            String job = element.getExtensions().getServiceTaskExtension().getJob();
            task.setJob(job);
        }
        return task;
    }
}
