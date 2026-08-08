package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.TimerEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.TimerEventType;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Fires a single due timer start job in its own transaction: marks it fired and starts a new
 * process instance at the timer start element. Separate bean so the {@link Transactional} proxy applies.
 *
 * WO-REL-13 (R-03): REQUIRES_NEW — the fire transaction is fully independent of the poll loop and
 * of every other job; a failure rolls back only this job's claim and side effects.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimerStartJobExecutor {

    private final DBService dbService;
    private final ActivityService activityService;
    private final BpmnService bpmnService;

    // WO-ENG-4: explicit business zone for timer cycle/cron resolution
    @Value("${zorrobpm.business-timezone:Asia/Almaty}")
    private ZoneId businessZone;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fire(UUID timerStartJobId, UUID processDefinitionId, String elementId) {
        if (!dbService.claimTimerStartJob(timerStartJobId)) {
            return; // Already claimed by another node
        }
        activityService.startProcessInstanceFromStartEvent(processDefinitionId, elementId, List.of());
        rescheduleIfRepeatingCycle(processDefinitionId, elementId);
    }

    /**
     * A repeating {@code timeCycle} timer start (unbounded {@code R/<duration>} or a cron expression) schedules
     * its next occurrence after firing, so the process keeps starting on schedule.
     */
    private void rescheduleIfRepeatingCycle(UUID processDefinitionId, String elementId) {
        BpmnProcessDefinitionModel model = bpmnService.getProcessDefinitionModelById(processDefinitionId);
        BpmnElementModel start = model.getElement(elementId);
        TimerEventExtensionModel timer = Optional.ofNullable(start)
            .map(BpmnElementModel::getExtensions)
            .map(BpmnElementExtensionModel::getTimerEventExtension)
            .orElse(null);
        if (timer == null || timer.getType() != TimerEventType.CYCLE) {
            return;
        }
        String expression = timer.getExpression();
        boolean infinite = TimerExpressions.isInfiniteCycle(expression);
        int repeatCount = TimerExpressions.repeatCount(expression);
        if (!infinite && repeatCount <= 0) {
            return; // one-shot or unsupported
        }
        Integer remaining = infinite ? null : repeatCount - 1;
        if (!infinite && remaining != null && remaining <= 0) {
            return; // done
        }
        // WO-ENG-4: use businessZone, not ZoneId.systemDefault()
        Instant next = TimerExpressions.firstOccurrence(expression, Instant.now(), businessZone);
        dbService.createTimerStartJob(model.getKey(), processDefinitionId, elementId, next);
        log.info("Rescheduled repeating timer start {} of {} for {} (remaining={})", elementId, model.getKey(), next, remaining);
    }
}
