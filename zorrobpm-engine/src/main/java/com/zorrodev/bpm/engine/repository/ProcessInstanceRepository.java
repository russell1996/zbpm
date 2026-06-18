package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.ActivityEntity;
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

    static Specification<ProcessInstanceEntity> byIds(List<UUID> ids) {
        return (root, query, criteriaBuilder) ->  root.get("id").in(ids);
    }

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ProcessInstanceEntity pi SET pi.completedAt = :completedAt WHERE pi.id = :id")
    void setCompletedAt(UUID id, Instant completedAt);

    /**
     * Acquires a row-level write lock on the process instance. Used to serialise all execution
     * that mutates a single instance (task completions, signals, timer/boundary firings, message
     * correlations) so concurrent async branches (e.g. two service tasks of a parallel split
     * finishing at once) cannot race on parallel-gateway joins or double-advance a token.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT pi FROM ProcessInstanceEntity pi WHERE pi.id = :id")
    Optional<ProcessInstanceEntity> findByIdForUpdate(UUID id);
}
