package com.zorrodev.bpm.engine.mapper;

import com.zorrodev.bpm.contract.model.MessageSubscription;
import com.zorrodev.bpm.engine.entity.MessageSubscriptionEntity;
import org.springframework.stereotype.Component;

@Component
public class MessageSubscriptionMapper {

    public MessageSubscription toDTO(MessageSubscriptionEntity entity) {
        MessageSubscription dto = new MessageSubscription();
        dto.setId(entity.getId());
        dto.setProcessInstanceId(entity.getProcessInstanceId());
        dto.setActivityId(entity.getActivityId());
        dto.setMessageName(entity.getMessageName());
        dto.setConsumed(entity.isConsumed());
        dto.setBoundaryElementId(entity.getBoundaryElementId());
        dto.setEventSubprocessId(entity.getEventSubprocessId());
        dto.setCorrelationKey(entity.getCorrelationKey());
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }
}
