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
import java.util.Optional;
import java.util.UUID;

public interface TimerJobRepository extends JpaRepository<TimerJobEntity, UUID>, JpaSpecificationExecutor<TimerJobEntity> {

    List<TimerJobEntity> findByFiredFalseAndDueAtLessThanEqual(Instant now);

    /**
     * WO-REL-17: the most recently fired timer job of a repeating (timeCycle) boundary timer on the
     * given host activity. The re-arm path ({@code EventTrigger}) reads the persisted
     * {@code remainingCount}/{@code expression}/{@code dueAt} from the fired job — exactly like
     * {@code TimerJobExecutor} does for catch timers — instead of recomputing the state from the
     * BPMN model (which never let a bounded cycle exhaust). The lookup is scoped by
     * (activity, boundary element) only; since WO-PERF-3 the job also carries the
     * {@code processInstanceId} (used by retention cleanup), but the re-arm lookup does not
     * need it to find the previous fired job.
     */
    Optional<TimerJobEntity> findFirstByActivityIdAndBoundaryElementIdAndFiredTrueOrderByCreatedAtDesc(
        UUID activityId, String boundaryElementId);

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

    /**
     * WO-REL-13: records a failed fire attempt per-job (attempts++/last_error) in its own
     * transaction, so a failing timer is visible for retry instead of being silently lost.
     */
    @Modifying
    @Query("UPDATE TimerJobEntity t SET t.attempts = t.attempts + 1, t.lastError = :error WHERE t.id = :id")
    int recordTimerJobError(@Param("id") UUID id, @Param("error") String error);

    @Modifying
    @Query("DELETE FROM TimerJobEntity t WHERE t.processInstanceId = :processInstanceId")
    void deleteByProcessInstanceId(@Param("processInstanceId") UUID processInstanceId);

    /**
     * WO-C8-35 раунд 6 (BLOCKER-6): ВСЕ строки граничных таймеров инстанса — и выстрелившие, и
     * ещё ожидающие. Правило готовности inclusive-join больше не спрашивает «взведена ли граница»,
     * а «исчерпана ли она»: одноразовый таймер после {@code claimTimerJob} больше не выстрелит,
     * значит граница отдала свою единственную ветвь и больше доставить не может.
     *
     * <p>Выстрелившие и ожидающие строки нужны ВМЕСТЕ, а не по отдельности: повторяющийся
     * цикл ({@code timeCycle}) после срабатывания получает НОВУЮ строку с {@code fired = false},
     * а multi-instance хост — по строке на копию. Граница исчерпана только когда по её outlet
     * есть выстрелившая строка И НЕТ ни одной невыстрелившей (решение считается в
     * {@code DBServiceImpl.getExhaustedBoundaryElementIds}).
     *
     * <p>Условие {@code boundaryElementId IS NOT NULL} — то же, что было у
     * {@code findArmedTimerJobs}: catch-таймеры (без границы) не имеют outlet'а и в правиле
     * готовности join не участвуют, их представляет строка activity хоста.
     */
    @Query("SELECT t FROM TimerJobEntity t WHERE t.processInstanceId = :processInstanceId "
        + "AND t.boundaryElementId IS NOT NULL")
    List<TimerJobEntity> findBoundaryTimerTriggers(UUID processInstanceId);

    static Specification<TimerJobEntity> byProcessInstanceId(UUID processInstanceId) {
        return (root, query, cb) -> cb.equal(root.get("processInstanceId"), processInstanceId);
    }

    static Specification<TimerJobEntity> byFired(boolean fired) {
        return (root, query, cb) -> cb.equal(root.get("fired"), fired);
    }
}
