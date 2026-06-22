package com.zorrodev.bpm.engine.mapper;

import com.zorrodev.bpm.contract.model.TimerJob;
import com.zorrodev.bpm.engine.entity.TimerJobEntity;
import org.springframework.stereotype.Component;

@Component
public class TimerJobMapper {

    public TimerJob toDTO(TimerJobEntity entity) {
        TimerJob dto = new TimerJob();
        dto.setId(entity.getId());
        dto.setActivityId(entity.getActivityId());
        dto.setProcessInstanceId(entity.getProcessInstanceId());
        dto.setDueAt(entity.getDueAt());
        dto.setFired(entity.isFired());
        dto.setBoundaryElementId(entity.getBoundaryElementId());
        dto.setEventSubprocessId(entity.getEventSubprocessId());
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }
}
