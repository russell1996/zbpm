package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.SignalSubscriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SignalSubscriptionRepository extends JpaRepository<SignalSubscriptionEntity, UUID> {

    List<SignalSubscriptionEntity> findByConsumedFalseAndSignalName(String signalName);

    @Modifying
    @Query("UPDATE SignalSubscriptionEntity e SET e.consumed = true WHERE e.id = :id AND e.consumed = false")
    int markConsumed(@Param("id") UUID id);
}
