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

    // WO-REL-31 CR-3: keyset-paged fan-out finders — batch ≤500, deterministic id-DESC order.
    // Cursor is the minimum id from the previous page (null = first page).

    List<SignalSubscriptionEntity> findFirst500ByConsumedFalseAndSignalNameOrderByIdDesc(String signalName);

    List<SignalSubscriptionEntity> findFirst500ByConsumedFalseAndSignalNameAndIdLessThanOrderByIdDesc(String signalName, UUID id);

    @Modifying
    @Query("UPDATE SignalSubscriptionEntity e SET e.consumed = true WHERE e.id = :id AND e.consumed = false")
    int markConsumed(@Param("id") UUID id);
}
