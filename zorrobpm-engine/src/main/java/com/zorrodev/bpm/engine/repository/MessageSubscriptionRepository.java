package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.MessageSubscriptionEntity;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface MessageSubscriptionRepository extends JpaRepository<MessageSubscriptionEntity, UUID>, JpaSpecificationExecutor<MessageSubscriptionEntity> {

    List<MessageSubscriptionEntity> findByConsumedFalseAndMessageNameAndProcessInstanceId(String messageName, UUID processInstanceId);

    List<MessageSubscriptionEntity> findByConsumedFalseAndMessageName(String messageName);

    List<MessageSubscriptionEntity> findByConsumedFalseAndMessageNameAndCorrelationKey(String messageName, String correlationKey);

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
