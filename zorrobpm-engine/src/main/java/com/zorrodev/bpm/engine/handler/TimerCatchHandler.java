package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.TimerEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.TimerEventType;
import com.zorrodev.bpm.engine.scheduler.TimerExpressions;
import com.zorrodev.bpm.engine.service.DBService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Handler for TIMER_CATCH_EVENT elements.
 * Parks the token and schedules a timer job.
 * Literal transfer from ActivityServiceImpl — no logic changes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimerCatchHandler implements ElementHandler, TypedElementHandler {

    private final DBService dbService;
    private final ElementSupport elementSupport;

    @Override
    public BpmnElementType elementType() { return BpmnElementType.TIMER_CATCH_EVENT; }

    @Override
    public ElementHandler handler() { return this; }

    @Override
    public void handle(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID processInstanceId = ctx.processInstanceId();
        UUID tokenId = ctx.tokenId();

        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
        Instant dueAt = elementSupport.computeDueAt(bpmnElement, processInstanceId);
        Integer remainingCount = computeRemainingCount(bpmnElement);
        // WO-REL-14: persist the cycle expression so re-arm (TimerJobExecutor) uses the real
        // interval instead of a hardcoded zero-second cycle. Null for non-CYCLE timers.
        String expression = cycleExpression(bpmnElement);
        dbService.createTimerJob(activityId, dueAt, null, remainingCount, expression, processInstanceId);
        log.info("{}/{}: Timer scheduled for {} at {}: {}/{} (remaining={})", processInstanceId, tokenId, bpmnElement.getId(), dueAt, activityId, bpmnElement.getType(), remainingCount);
    }

    private Integer computeRemainingCount(BpmnElementModel bpmnElement) {
        return Optional.ofNullable(bpmnElement.getExtensions())
            .map(BpmnElementExtensionModel::getTimerEventExtension)
            .filter(t -> t.getType() == TimerEventType.CYCLE)
            .map(t -> TimerExpressions.repeatCount(t.getExpression()))
            .filter(count -> count > 0)
            .map(count -> count - 1)
            .orElse(null);
    }

    private String cycleExpression(BpmnElementModel bpmnElement) {
        return Optional.ofNullable(bpmnElement.getExtensions())
            .map(BpmnElementExtensionModel::getTimerEventExtension)
            .filter(t -> t.getType() == TimerEventType.CYCLE)
            .map(TimerEventExtensionModel::getExpression)
            .orElse(null);
    }
}
