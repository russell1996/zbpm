package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.SignalSubscriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SignalSubscriptionRepository extends JpaRepository<SignalSubscriptionEntity, UUID> {

    List<SignalSubscriptionEntity> findByConsumedFalseAndSignalName(String signalName);
}
