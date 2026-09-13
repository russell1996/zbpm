package com.zorrodev.bpm.engine.metrics;

import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * WO-OBS-3: updates oldest user-task age gauge (zbpm.usertask.age.max).
 * Runs every 15s (same as Prometheus scrape), best-effort — failures are logged, not propagated.
 * DLQ depth is NOT updated here — rabbitmq_queue_messages{queue="zorrobpm.complete-service-task.dlq"}
 * from WO-OBS-2 already covers it; no custom zbpm.dlq.depth needed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ObservabilityMetricsUpdater {

    private final BpmMetrics bpmMetrics;
    private final UserTaskRepository userTaskRepository;

    @Value("${zorrobpm.observability.metrics-interval-ms:15000}")
    private long intervalMs;

    @Scheduled(fixedDelayString = "${zorrobpm.observability.metrics-interval-ms:15000}")
    public void update() {
        try {
            updateUsertaskAgeMax();
        } catch (Exception e) {
            log.debug("Failed to update usertask age", e);
        }
    }

    private void updateUsertaskAgeMax() {
        Instant oldest = userTaskRepository.findOldestOpenCreatedAt();
        if (oldest == null) {
            bpmMetrics.setUsertaskAgeMax(0);
        } else {
            long ageSec = Duration.between(oldest, Instant.now()).getSeconds();
            if (ageSec < 0) ageSec = 0;
            bpmMetrics.setUsertaskAgeMax(ageSec);
        }
    }
}
