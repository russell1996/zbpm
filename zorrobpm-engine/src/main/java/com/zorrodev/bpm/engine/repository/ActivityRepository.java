package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ActivityRepository extends JpaRepository<ActivityEntity, UUID> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ActivityEntity e SET e.status = :status, e.completedAt = :completedAt WHERE e.id = :id")
    void setStatusAndCompletedAt(UUID id, ActivityStatus status, Instant completedAt);

    List<ActivityEntity> findByTokenAndBpmnElementId(UUID token, String bpmnElementId);

    /**
     * WO-REL-30 (B-3): activity + its process-instance lock in ONE statement.
     * Theta-join keeps the {@code activities} table free of a new FK (D-1 scope!):
     * the row lock on {@code process_instances} serialises all execution touching
     * the instance exactly like the old read-then-lock pair, but with no race
     * window between the read and the lock. H2 + PostgreSQL: same JPQL.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM ActivityEntity a, ProcessInstanceEntity p "
        + "WHERE a.id = :id AND p.id = a.processInstanceId")
    Optional<ActivityEntity> findByIdForUpdate(UUID id);

    List<ActivityEntity> findByProcessInstanceIdAndStatusIn(UUID processInstanceId, Collection<ActivityStatus> statuses);

    /**
     * WO-REL-31 CR-3: deterministic iteration order for getActiveActivities (triggerConditionalEvents
     * fan-out) — activities processed in stable id-ASC order, never a DB/heap-order surprise.
     */
    List<ActivityEntity> findByProcessInstanceIdAndStatusInOrderByIdAsc(UUID processInstanceId, Collection<ActivityStatus> statuses);

    List<ActivityEntity> findByProcessInstanceIdOrderByCreatedAtAsc(UUID processInstanceId);

    org.springframework.data.domain.Page<ActivityEntity> findByProcessInstanceIdOrderByCreatedAtAsc(UUID processInstanceId, org.springframework.data.domain.Pageable pageable);

    List<ActivityEntity> findByTokenAndStatusIn(UUID token, Collection<ActivityStatus> statuses);

    List<ActivityEntity> findByTokenAndBpmnElementIdAndStatusIn(UUID token, String bpmnElementId, Collection<ActivityStatus> statuses);

    /**
     * WO-C8-28: activities with an open canceling-listener phase on one token —
     * the last-closer check for a deferred boundary continuation (all must close
     * before the token proceeds; serialized by the process-instance lock).
     */
    List<ActivityEntity> findByTokenAndPendingCancelingListenerIndexIsNotNull(UUID token);

    /**
     * WO-C8-28: activities with an open canceling-listener phase in one instance —
     * the last-closer check for a deferred process-cancel tail.
     */
    List<ActivityEntity> findByProcessInstanceIdAndPendingCancelingListenerIndexIsNotNull(UUID processInstanceId);

    /**
     * WO-REL-27: stuck service tasks — CREATED service tasks whose createdAt is
     * older than the dispatch-timeout cutoff. Uses FOR UPDATE SKIP LOCKED via
     * the batch processor's short TX so concurrent watchdog instances don't
     * duplicate incidents. Batch size limits locking.
     *
     * <p>WO-REL-35 (F09): the {@code NOT EXISTS} open-incident filter sits in
     * the SQL itself, BEFORE {@code LIMIT}. The old Java-side post-filter
     * re-selected the same incidented rows every cycle: with the first
     * {@code batchSize} tasks incidented-but-unresolved, every later stuck task
     * starved forever behind the page. Same native SQL on PostgreSQL and H2.
     */
    @Query(value = "SELECT * FROM activities a WHERE a.type = 'SERVICE_TASK' AND a.status = 'CREATED' AND a.created_at < :cutoff "
        + "AND NOT EXISTS (SELECT 1 FROM incidents i WHERE i.activity_id = a.id AND i.completed_at IS NULL) "
        + "ORDER BY a.created_at LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<ActivityEntity> findStuckServiceTasksLocked(Instant cutoff, int limit);

}
