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

    /**
     * WO-C8-35 (CR-09, ШАГ 2/B1; раунд 4 — контракт уточнён): this instance's unconsumed
     * signal subscriptions, UNFILTERED — the query returns EVERY pending row of the instance
     * (boundary trigger, event-sub-process trigger and plain catch alike). Callers narrow it: only
     * a row with a non-null boundary element id is an armed trigger, and the event-sub-process start
     * trigger is deliberately not counted as a branch deliverer (Решение 2). A plain signal catch needs
     * no entry here because its host activity row already covers it in the live-execution universe.
     */
    List<SignalSubscriptionEntity> findByProcessInstanceIdAndConsumedFalse(UUID processInstanceId);

    @Modifying
    @Query("UPDATE SignalSubscriptionEntity e SET e.consumed = true WHERE e.id = :id AND e.consumed = false")
    int markConsumed(@Param("id") UUID id);
}
