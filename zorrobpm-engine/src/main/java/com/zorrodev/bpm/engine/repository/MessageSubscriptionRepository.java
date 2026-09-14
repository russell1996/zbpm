package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.MessageSubscriptionEntity;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface MessageSubscriptionRepository extends JpaRepository<MessageSubscriptionEntity, UUID>, JpaSpecificationExecutor<MessageSubscriptionEntity> {

    List<MessageSubscriptionEntity> findByConsumedFalseAndMessageNameAndProcessInstanceId(String messageName, UUID processInstanceId);

    List<MessageSubscriptionEntity> findByConsumedFalseAndMessageName(String messageName);

    List<MessageSubscriptionEntity> findByConsumedFalseAndMessageNameAndCorrelationKey(String messageName, String correlationKey);

    // WO-REL-31 CR-3: keyset-paged fan-out finders — batch ≤500, deterministic id-DESC order.
    // Cursor is the minimum id from the previous page (null = first page). Pages of non-interrupting
    // event-subprocess subscriptions are never revisited (ids from earlier pages are always > cursor),
    // so consumed=false re-firers don't create infinite loops.

    List<MessageSubscriptionEntity> findFirst500ByConsumedFalseAndMessageNameOrderByIdDesc(String messageName);

    List<MessageSubscriptionEntity> findFirst500ByConsumedFalseAndMessageNameAndIdLessThanOrderByIdDesc(String messageName, UUID id);

    List<MessageSubscriptionEntity> findFirst500ByConsumedFalseAndMessageNameAndProcessInstanceIdOrderByIdDesc(String messageName, UUID processInstanceId);

    List<MessageSubscriptionEntity> findFirst500ByConsumedFalseAndMessageNameAndProcessInstanceIdAndIdLessThanOrderByIdDesc(String messageName, UUID processInstanceId, UUID id);

    List<MessageSubscriptionEntity> findFirst500ByConsumedFalseAndMessageNameAndCorrelationKeyOrderByIdDesc(String messageName, String correlationKey);

    List<MessageSubscriptionEntity> findFirst500ByConsumedFalseAndMessageNameAndCorrelationKeyAndIdLessThanOrderByIdDesc(String messageName, String correlationKey, UUID id);

    @Modifying
    @Query("UPDATE MessageSubscriptionEntity e SET e.consumed = true WHERE e.id = :id AND e.consumed = false")
    int markConsumed(@Param("id") UUID id);

    static Specification<MessageSubscriptionEntity> byProcessInstanceId(UUID processInstanceId) {
        return (root, query, cb) -> cb.equal(root.get("processInstanceId"), processInstanceId);
    }

    static Specification<MessageSubscriptionEntity> byConsumed(boolean consumed) {
        return (root, query, cb) -> cb.equal(root.get("consumed"), consumed);
    }

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM MessageSubscriptionEntity m WHERE m.processInstanceId = :processInstanceId")
    void deleteByProcessInstanceId(@org.springframework.data.repository.query.Param("processInstanceId") UUID processInstanceId);
}
