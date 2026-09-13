package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.metrics.BpmMetrics;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.service.db.IncidentDbOperations;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * WO-REL-27: stuck service-task watchdog.
 * Finds service tasks stuck in CREATED beyond dispatch-timeout and raises an
 * incident SERVICE_TASK_DISPATCH_TIMEOUT idempotently. Does NOT re-dispatch the job.
 * Multi-instance safe via FOR UPDATE SKIP LOCKED batch selection.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StuckServiceTaskWatchdog {

    private final ActivityRepository activityRepository;
    private final IncidentRepository incidentRepository;
    private final IncidentDbOperations incidentDbOperations;
    private final BpmMetrics bpmMetrics;

    @Value("${zorrobpm.servicetask.dispatch-timeout:15m}")
    private Duration dispatchTimeout;

    @Value("${zorrobpm.servicetask.watchdog-batch-size:100}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${zorrobpm.servicetask.watchdog-interval-ms:60000}")
    public void checkStuckTasks() {
        if (dispatchTimeout == null || dispatchTimeout.isZero() || dispatchTimeout.isNegative()) {
            log.debug("StuckServiceTaskWatchdog disabled (dispatch-timeout=0)");
            return;
        }
        try {
            int total = processBatch();
            if (total > 0) {
                log.info("StuckServiceTaskWatchdog: processed {} stuck tasks", total);
            }
        } catch (Exception e) {
            log.error("StuckServiceTaskWatchdog failed", e);
        }
    }

    @Transactional
    public int processBatch() {
        if (dispatchTimeout == null || dispatchTimeout.isZero() || dispatchTimeout.isNegative()) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(dispatchTimeout);
        List<ActivityEntity> stuck = activityRepository.findStuckServiceTasksLocked(cutoff, batchSize);
        // WO-REL-27: metric — visible even before incident is persisted
        bpmMetrics.setStuckServiceTasks(stuck.size());
        if (stuck.isEmpty()) {
            return 0;
        }
        int raised = 0;
        for (ActivityEntity activity : stuck) {
            // idempotency: skip if open incident already exists for this activity
            List<?> open = incidentRepository.findByActivityIdInAndCompletedAtIsNull(List.of(activity.getId()));
            if (!open.isEmpty()) {
                log.debug("Stuck task {} already has open incident, skipping", activity.getId());
                continue;
            }
            String message = "SERVICE_TASK_DISPATCH_TIMEOUT: no worker response within " + dispatchTimeout
                    + " (service task " + activity.getId() + " / " + activity.getBpmnElementId() + ")";
            incidentDbOperations.createIncident(activity.getId(), message);
            raised++;
            log.warn("Stuck service task {} (element {}, createdAt {}) exceeded dispatch-timeout {} — incident raised",
                    activity.getId(), activity.getBpmnElementId(), activity.getCreatedAt(), dispatchTimeout);
        }
        return raised;
    }

    // exposed for tests / PgIT to override timeout without Spring rewire
    void setDispatchTimeout(Duration timeout) {
        this.dispatchTimeout = timeout;
    }

    void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }
}
