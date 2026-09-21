package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.exception.EngineException;
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
    private final IncidentService incidentService;

    @Override
    public BpmnElementType elementType() { return BpmnElementType.TIMER_CATCH_EVENT; }

    @Override
    public ElementHandler handler() { return this; }

    @Override
    public void handle(ExecutionCtx ctx, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID processInstanceId = ctx.processInstanceId();
        UUID tokenId = ctx.tokenId();

        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
        final Instant dueAt;
        try {
            dueAt = elementSupport.computeDueAt(bpmnElement, processInstanceId);
        } catch (EngineException e) {
            // WO-DIFF-7 (Raxon finding #12, S-055): a FEEL timer expression that fails
            // to evaluate (e.g. `=missingVar` → null) parks an incident on THIS timer
            // element instead of aborting the whole start (Zeebe: EXTRACT_VALUE_ERROR
            // incident, instance created). Same canonical path as ActivityServiceImpl's
            // element-failure catch (IncidentService, incl. WO-REL-40 fallback), so a
            // later resolveIncident with the missing variable re-executes this element
            // and re-arms the timer (re-evaluation, Zeebe parity). Only EngineException
            // (eval/type failure) is parked — infra failures still propagate.
            incidentService.raiseIncident(processInstanceId, tokenId, bpmnElement, e);
            return;
        }
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
