package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.TimerJobEntity;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TimerJobRepository extends JpaRepository<TimerJobEntity, UUID>, JpaSpecificationExecutor<TimerJobEntity> {

    List<TimerJobEntity> findByFiredFalseAndDueAtLessThanEqual(Instant now);

    /**
     * L6 FIX: FOR UPDATE SKIP LOCKED prevents two pollers from picking up the same timer jobs.
     * Row locks are held until the calling transaction commits.
     * WO-REL-11: LIMIT :batchSize caps the number of rows locked per poll to avoid unbounded locking.
     */
    @Query(value = "SELECT * FROM timer_jobs WHERE fired = false AND due_at <= :now ORDER BY due_at ASC LIMIT :batchSize FOR UPDATE SKIP LOCKED",
           nativeQuery = true)
    List<TimerJobEntity> findDueLocked(@Param("now") Instant now, @Param("batchSize") int batchSize);

    @Modifying
    @Query("UPDATE TimerJobEntity t SET t.fired = true WHERE t.id = :id AND t.fired = false")
    int claimTimerJob(@Param("id") UUID id);

    @Modifying
    @Query("DELETE FROM TimerJobEntity t WHERE t.processInstanceId = :processInstanceId")
    void deleteByProcessInstanceId(@Param("processInstanceId") UUID processInstanceId);

    static Specification<TimerJobEntity> byProcessInstanceId(UUID processInstanceId) {
        return (root, query, cb) -> cb.equal(root.get("processInstanceId"), processInstanceId);
    }

    static Specification<TimerJobEntity> byFired(boolean fired) {
        return (root, query, cb) -> cb.equal(root.get("fired"), fired);
    }
}
