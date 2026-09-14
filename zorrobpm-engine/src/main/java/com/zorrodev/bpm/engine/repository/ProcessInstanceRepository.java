package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProcessInstanceRepository extends JpaRepository<ProcessInstanceEntity, UUID>, JpaSpecificationExecutor<ProcessInstanceEntity> {

    static Specification<ProcessInstanceEntity> byProcessInstanceId(UUID processInstanceId) {
        return (root, query, criteriaBuilder) ->  criteriaBuilder.equal(root.get("processInstanceId"), processInstanceId);
    }

    static Specification<ProcessInstanceEntity> byId(UUID id) {
        return (root, query, criteriaBuilder) ->  criteriaBuilder.equal(root.get("id"), id);
    }

    static Specification<ProcessInstanceEntity> byParentProcessInstanceId(UUID parentProcessInstanceId) {
        return (root, query, criteriaBuilder) -> {
            var subquery = query.subquery(UUID.class);
            var activity = subquery.from(ActivityEntity.class);
            subquery.select(activity.get("id"))
                .where(criteriaBuilder.equal(activity.get("processInstanceId"), parentProcessInstanceId));
            return root.get("parentActivityId").in(subquery);
        };
    }

    static Specification<ProcessInstanceEntity> byProcessDefinitionId(UUID processDefinitionId) {
        return (root, query, criteriaBuilder) ->  criteriaBuilder.equal(root.get("processDefinitionId"), processDefinitionId);
    }

    /** Matches instances whose definition has the given key (join via a process-definition subquery). */
    static Specification<ProcessInstanceEntity> byProcessDefinitionKey(String key) {
        return (root, query, cb) -> {
            var subquery = query.subquery(UUID.class);
            var pd = subquery.from(ProcessDefinitionEntity.class);
            subquery.select(pd.get("id")).where(cb.equal(pd.get("key"), key));
            return root.get("processDefinitionId").in(subquery);
        };
    }

    static Specification<ProcessInstanceEntity> byProcessDefinitionVersion(Integer version) {
        return (root, query, cb) -> {
            var subquery = query.subquery(UUID.class);
            var pd = subquery.from(ProcessDefinitionEntity.class);
            subquery.select(pd.get("id")).where(cb.equal(pd.get("version"), version));
            return root.get("processDefinitionId").in(subquery);
        };
    }

    static Specification<ProcessInstanceEntity> byIds(List<UUID> ids) {
        return (root, query, criteriaBuilder) ->  root.get("id").in(ids);
    }

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ProcessInstanceEntity pi SET pi.completedAt = :completedAt WHERE pi.id = :id")
    void setCompletedAt(UUID id, Instant completedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ProcessInstanceEntity pi SET pi.cancelled = :cancelled WHERE pi.id = :id")
    void setCancelled(UUID id, boolean cancelled);

    /**
     * Acquires a row-level write lock on the process instance. Used to serialise all execution
     * that mutates a single instance (task completions, signals, timer/boundary firings, message
     * correlations) so concurrent async branches (e.g. two service tasks of a parallel split
     * finishing at once) cannot race on parallel-gateway joins or double-advance a token.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT pi FROM ProcessInstanceEntity pi WHERE pi.id = :id")
    Optional<ProcessInstanceEntity> findByIdForUpdate(UUID id);

    /**
     * WO-REL-31 CR-4: definition key in one join query instead of instance→definition
     * two-step resolution. Empty when the instance or its definition is missing —
     * same branches the two-step path used to skip.
     */
    @Query("SELECT pd.key FROM ProcessInstanceEntity pi JOIN ProcessDefinitionEntity pd ON pd.id = pi.processDefinitionId WHERE pi.id = :id")
    Optional<String> findDefinitionKeyById(UUID id);
}
