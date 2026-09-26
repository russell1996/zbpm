package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.MessageStartSubscriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.List;
import java.util.UUID;

public interface MessageStartSubscriptionRepository extends JpaRepository<MessageStartSubscriptionEntity, UUID> {

    List<MessageStartSubscriptionEntity> findByMessageName(String messageName);

    @Modifying
    void deleteByProcessKey(String processKey);
}
