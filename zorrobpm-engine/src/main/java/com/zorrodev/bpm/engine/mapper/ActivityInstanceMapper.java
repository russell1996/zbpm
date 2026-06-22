package com.zorrodev.bpm.engine.mapper;

import com.zorrodev.bpm.contract.model.ActivityInstance;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import org.springframework.stereotype.Component;

@Component
public class ActivityInstanceMapper {

    public ActivityInstance toDTO(ActivityEntity entity) {
        ActivityInstance dto = new ActivityInstance();
        dto.setId(entity.getId());
        dto.setProcessInstanceId(entity.getProcessInstanceId());
        dto.setBpmnElementId(entity.getBpmnElementId());
        dto.setType(entity.getType() == null ? null : entity.getType().name());
        dto.setStatus(entity.getStatus() == null ? null : entity.getStatus().name());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setCompletedAt(entity.getCompletedAt());
        return dto;
    }
}
