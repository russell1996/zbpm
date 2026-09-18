package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.TimerStartJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TimerStartJobRepository extends JpaRepository<TimerStartJobEntity, UUID> {

    List<TimerStartJobEntity> findByFiredFalseAndDueAtLessThanEqual(Instant now);

    /**
     * L6 FIX: FOR UPDATE SKIP LOCKED prevents two pollers from picking up the same timer start jobs.
     * Row locks are held until the calling transaction commits.
     * WO-REL-11: LIMIT :batchSize caps the number of rows locked per poll to avoid unbounded locking.
     */
    @Query(value = "SELECT * FROM timer_start_jobs WHERE fired = false AND due_at <= :now ORDER BY due_at ASC LIMIT :batchSize FOR UPDATE SKIP LOCKED",
           nativeQuery = true)
    List<TimerStartJobEntity> findDueLocked(@Param("now") Instant now, @Param("batchSize") int batchSize);

    @Modifying
    @Query("UPDATE TimerStartJobEntity t SET t.fired = true WHERE t.id = :id AND t.fired = false")
    int claimTimerStartJob(@Param("id") UUID id);

    /**
     * WO-REL-13: records a failed fire attempt per-job (attempts++/last_error) in its own
     * transaction, so a failing timer start is visible for retry instead of being silently lost.
     */
    @Modifying
    @Query("UPDATE TimerStartJobEntity t SET t.attempts = t.attempts + 1, t.lastError = :error WHERE t.id = :id")
    int recordTimerStartJobError(@Param("id") UUID id, @Param("error") String error);

    @Modifying
    void deleteByProcessKey(String processKey);
}
