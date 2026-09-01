package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.engine.dto.TimerJob;
import com.zorrodev.bpm.engine.dto.TimerStartJob;
import com.zorrodev.bpm.engine.entity.TimerJobEntity;
import com.zorrodev.bpm.engine.entity.TimerStartJobEntity;
import com.zorrodev.bpm.engine.repository.TimerJobRepository;
import com.zorrodev.bpm.engine.repository.TimerStartJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * WO-DEBT-1m: домен Timers — реализация.
 * Перенесено 1:1 из DBServiceImpl (14 методов).
 */
@Service
@RequiredArgsConstructor
public class TimerDbOperationsImpl implements TimerDbOperations {

    private final TimerJobRepository timerJobRepository;
    private final TimerStartJobRepository timerStartJobRepository;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public UUID createTimerJob(UUID activityId, Instant dueAt, String boundaryElementId, Integer remainingCount, String expression, UUID processInstanceId) {
        UUID id = UUID.randomUUID();
        TimerJobEntity entity = new TimerJobEntity();
        entity.setId(id);
        entity.setActivityId(activityId);
        entity.setDueAt(dueAt);
        entity.setFired(false);
        entity.setCreatedAt(Instant.now());
        entity.setBoundaryElementId(boundaryElementId);
        entity.setRemainingCount(remainingCount);
        entity.setExpression(expression);
        entity.setProcessInstanceId(processInstanceId);
        timerJobRepository.save(entity);
        return id;
    }

    @Override
    public UUID createEventSubprocessTimerJob(UUID processInstanceId, Instant dueAt, String eventSubprocessId) {
        UUID id = UUID.randomUUID();
        TimerJobEntity entity = new TimerJobEntity();
        entity.setId(id);
        entity.setActivityId(null);
        entity.setDueAt(dueAt);
        entity.setFired(false);
        entity.setCreatedAt(Instant.now());
        entity.setProcessInstanceId(processInstanceId);
        entity.setEventSubprocessId(eventSubprocessId);
        timerJobRepository.save(entity);
        return id;
    }

    @Override
    public void createTimerStartJob(String processKey, UUID processDefinitionId, String elementId, Instant dueAt) {
        createTimerStartJob(processKey, processDefinitionId, elementId, dueAt, null);
    }

    @Override
    public void createTimerStartJob(String processKey, UUID processDefinitionId, String elementId, Instant dueAt, Integer remainingCount) {
        TimerStartJobEntity entity = new TimerStartJobEntity();
        entity.setId(UUID.randomUUID());
        entity.setProcessKey(processKey);
        entity.setProcessDefinitionId(processDefinitionId);
        entity.setElementId(elementId);
        entity.setDueAt(dueAt);
        entity.setFired(false);
        entity.setCreatedAt(Instant.now());
        entity.setRemainingCount(remainingCount);
        timerStartJobRepository.save(entity);
    }

    @Override
    public void deleteTimerJobsByProcessInstanceId(UUID processInstanceId) {
        timerJobRepository.deleteByProcessInstanceId(processInstanceId);
    }

    @Override
    public void deleteTimerStartJobsByKey(String processKey) {
        timerStartJobRepository.deleteByProcessKey(processKey);
    }

    @Override
    public List<TimerJob> findDueTimerJobs(Instant now) {
        return timerJobRepository.findByFiredFalseAndDueAtLessThanEqual(now).stream()
            .map(e -> {
                TimerJob job = new TimerJob();
                job.setId(e.getId());
                job.setActivityId(e.getActivityId());
                job.setDueAt(e.getDueAt());
                job.setCreatedAt(e.getCreatedAt());
                job.setBoundaryElementId(e.getBoundaryElementId());
                job.setProcessInstanceId(e.getProcessInstanceId());
                job.setEventSubprocessId(e.getEventSubprocessId());
                job.setRemainingCount(e.getRemainingCount());
                job.setExpression(e.getExpression());
                return job;
            })
            .toList();
    }

    /**
     * WO-REL-13: candidate selection runs in its own SHORT transaction — the SKIP LOCKED row locks
     * are released as soon as the SELECT returns, before any job is fired. Double execution is then
     * prevented by the atomic CAS claim inside each fire's REQUIRES_NEW transaction.
     */
    @Override
    @Transactional
    public List<TimerJob> findDueTimerJobsLocked(Instant now, int batchSize) {
        return timerJobRepository.findDueLocked(now, batchSize).stream()
            .map(e -> {
                TimerJob job = new TimerJob();
                job.setId(e.getId());
                job.setActivityId(e.getActivityId());
                job.setDueAt(e.getDueAt());
                job.setCreatedAt(e.getCreatedAt());
                job.setBoundaryElementId(e.getBoundaryElementId());
                job.setProcessInstanceId(e.getProcessInstanceId());
                job.setEventSubprocessId(e.getEventSubprocessId());
                job.setRemainingCount(e.getRemainingCount());
                job.setExpression(e.getExpression());
                return job;
            })
            .toList();
    }

    @Override
    public List<TimerStartJob> findDueTimerStartJobs(Instant now) {
        return timerStartJobRepository.findByFiredFalseAndDueAtLessThanEqual(now).stream()
            .map(e -> {
                TimerStartJob job = new TimerStartJob();
                job.setId(e.getId());
                job.setProcessKey(e.getProcessKey());
                job.setProcessDefinitionId(e.getProcessDefinitionId());
                job.setElementId(e.getElementId());
                job.setDueAt(e.getDueAt());
                job.setRemainingCount(e.getRemainingCount());
                return job;
            })
            .toList();
    }

    /**
     * WO-REL-13: candidate selection runs in its own SHORT transaction (see findDueTimerJobsLocked).
     */
    @Override
    @Transactional
    public List<TimerStartJob> findDueTimerStartJobsLocked(Instant now, int batchSize) {
        return timerStartJobRepository.findDueLocked(now, batchSize).stream()
            .map(e -> {
                TimerStartJob job = new TimerStartJob();
                job.setId(e.getId());
                job.setProcessKey(e.getProcessKey());
                job.setProcessDefinitionId(e.getProcessDefinitionId());
                job.setElementId(e.getElementId());
                job.setDueAt(e.getDueAt());
                job.setRemainingCount(e.getRemainingCount());
                return job;
            })
            .toList();
    }

    @Override
    @Transactional
    public boolean claimTimerJob(UUID timerJobId) {
        // WO-REL-13: NON-BLOCKING claim. The SKIP LOCKED row lock from findDueTimerJobsLocked is
        // released as soon as the selection transaction commits, so two pollers (multinode) can
        // select the SAME due row. A plain UPDATE here would then block on the other poller's
        // uncommitted row lock → cross-poller deadlock. FOR UPDATE SKIP LOCKED makes the claim
        // either win instantly or lose instantly (row already locked → skipped → 0 rows).
        List<UUID> locked = jdbcTemplate.queryForList(
            "SELECT id FROM timer_jobs WHERE id = ? AND fired = false FOR UPDATE SKIP LOCKED",
            UUID.class, timerJobId);
        if (locked.isEmpty()) {
            return false;
        }
        return timerJobRepository.claimTimerJob(timerJobId) > 0;
    }

    @Override
    @Transactional
    public boolean claimTimerStartJob(UUID timerStartJobId) {
        // WO-REL-13: NON-BLOCKING claim — see claimTimerJob.
        List<UUID> locked = jdbcTemplate.queryForList(
            "SELECT id FROM timer_start_jobs WHERE id = ? AND fired = false FOR UPDATE SKIP LOCKED",
            UUID.class, timerStartJobId);
        if (locked.isEmpty()) {
            return false;
        }
        return timerStartJobRepository.claimTimerStartJob(timerStartJobId) > 0;
    }

    @Override
    @Transactional
    public void recordTimerJobError(UUID timerJobId, String errorMessage) {
        timerJobRepository.recordTimerJobError(timerJobId, errorMessage);
    }

    @Override
    @Transactional
    public void recordTimerStartJobError(UUID timerStartJobId, String errorMessage) {
        timerStartJobRepository.recordTimerStartJobError(timerStartJobId, errorMessage);
    }
}
