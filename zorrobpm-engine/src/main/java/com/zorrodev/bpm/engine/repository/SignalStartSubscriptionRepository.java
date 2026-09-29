package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.SignalStartSubscriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.List;
import java.util.UUID;

public interface SignalStartSubscriptionRepository extends JpaRepository<SignalStartSubscriptionEntity, UUID> {

    List<SignalStartSubscriptionEntity> findBySignalName(String signalName);

    @Modifying
    void deleteByProcessKey(String processKey);
}
