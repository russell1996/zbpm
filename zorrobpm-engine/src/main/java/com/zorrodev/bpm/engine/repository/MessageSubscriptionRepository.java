package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.MessageSubscriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MessageSubscriptionRepository extends JpaRepository<MessageSubscriptionEntity, UUID> {

    List<MessageSubscriptionEntity> findByConsumedFalseAndMessageNameAndProcessInstanceId(String messageName, UUID processInstanceId);

    List<MessageSubscriptionEntity> findByConsumedFalseAndMessageName(String messageName);
}
