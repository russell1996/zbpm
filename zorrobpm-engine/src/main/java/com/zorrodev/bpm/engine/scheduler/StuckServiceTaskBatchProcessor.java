package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.metrics.BpmMetrics;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.service.db.IncidentDbOperations;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * WO-REL-35 (F08): transactional batch processor for the stuck service-task watchdog.
 *
 * <p>Separated from {@code StuckServiceTaskWatchdog} so the {@code @Transactional}
 * boundary actually applies: the scheduler calls THIS bean (a different Spring bean,
 * through the proxy), never a self-invoked method. Same pattern as
 * {@code RetentionJob} → {@code RetentionBatchProcessor}.
 *
 * <p>One call = one transaction: {@code SELECT ... FOR UPDATE SKIP LOCKED} plus
 * all incident creations commit or roll back together. A fault mid-batch rolls the
 * whole batch back — no half-created incidents (criterion 1).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StuckServiceTaskBatchProcessor {

    private final ActivityRepository activityRepository;
    private final IncidentDbOperations incidentDbOperations;
    private final BpmMetrics bpmMetrics;

    /**
     * Processes one batch of stuck service tasks.
     *
     * <p>WO-REL-35 (F09): activities that already carry an open incident never
     * reach this loop — the {@code NOT EXISTS} filter sits in the SQL itself,
     * before {@code LIMIT}. The old Java-side post-filter re-selected the same
     * incidented rows every cycle and starved every stuck task behind the page.
     *
     * @param cutoff tasks with {@code created_at} older than this are stuck
     * @param batchSize max rows locked per pass
     * @param dispatchTimeout reported in the incident message (diagnostics only)
     * @return number of incidents raised
     */
    @Transactional
    public int processBatch(Instant cutoff, int batchSize, Duration dispatchTimeout) {
        List<ActivityEntity> stuck = activityRepository.findStuckServiceTasksLocked(cutoff, batchSize);
        // WO-REL-27: metric — visible even before incidents are persisted
        bpmMetrics.setStuckServiceTasks(stuck.size());
        if (stuck.isEmpty()) {
            return 0;
        }
        int raised = 0;
        for (ActivityEntity activity : stuck) {
            String message = "SERVICE_TASK_DISPATCH_TIMEOUT: no worker response within " + dispatchTimeout
                    + " (service task " + activity.getId() + " / " + activity.getBpmnElementId() + ")";
            incidentDbOperations.createIncident(activity.getId(), message);
            raised++;
            log.warn("Stuck service task {} (element {}, createdAt {}) exceeded dispatch-timeout {} — incident raised",
                    activity.getId(), activity.getBpmnElementId(), activity.getCreatedAt(), dispatchTimeout);
        }
        return raised;
    }
}
