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

    /** WO-C8-34 (CR-04): child instances started by one call activity row. */
    List<ProcessInstanceEntity> findByParentActivityId(UUID parentActivityId);

    /**
     * WO-AUDIT-12 (F-02): дочерние инстансы (call activity) ДАННОГО инстанса —
     * все, кто ещё не терминален. Родитель известен по строке активности
     * ({@code process_instances.parent_activity_id → activities.id}), поэтому
     * «дети инстанса» = инстансы, чья parent-строка принадлежит этому
     * инстансу. Один запрос вместо «перебрать активности инстанса и для
     * каждой спросить findByParentActivityId» (N+1 на пути отмены).
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} — не косметика: путь завершения ребёнка
     * держит instance-lock РЕБЁНКА и потом просит instance-lock РОДИТЕЛЯ
     * (child→parent), а отмена родителя держит lock родителя и берёт строки
     * детей (parent→child). Без SKIP LOCKED эти два порядка образуют ABBA:
     * взаимное ожидание → PostgreSQL рвёт одну транзакцию (40P01). SKIP LOCKED
     * не ждёт занятую строку: ребёнок, который прямо сейчас завершается, будет
     * добил собственным хвостом (его завершение подавлено guard'ом
     * «родитель терминален»), а ребёнок в покое (часы/дни — сам сценарий F-02)
     * убивается каскадом как обычно.
     */
    @Query(value = "SELECT pi.* FROM process_instances pi WHERE pi.parent_activity_id IN "
        + "(SELECT a.id FROM activities a WHERE a.process_instance_id = :processInstanceId) "
        + "AND pi.completed_at IS NULL FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<ProcessInstanceEntity> findRunningChildrenByParentInstanceIdForUpdateSkipLocked(UUID processInstanceId);

    /**
     * WO-REL-31 CR-4: definition key in one join query instead of instance→definition
     * two-step resolution. Empty when the instance or its definition is missing —
     * same branches the two-step path used to skip.
     */
    @Query("SELECT pd.key FROM ProcessInstanceEntity pi JOIN ProcessDefinitionEntity pd ON pd.id = pi.processDefinitionId WHERE pi.id = :id")
    Optional<String> findDefinitionKeyById(UUID id);
}
