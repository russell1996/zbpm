package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.BoundaryEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.EventDefinitionExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.SubProcessExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.TimerEventExtensionModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.engine.dto.SignalSubscription;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.TimerJobEntity;
import com.zorrodev.bpm.engine.repository.TimerJobRepository;
import com.zorrodev.bpm.engine.scheduler.TimerExpressions;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ScriptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Collaborator extracted from ActivityServiceImpl (WO-AUD-22).
 * Encapsulates the event-triggering machinery: boundary firing, conditional evaluation,
 * event sub-process triggering, and instance bootstrapping at a specific start element.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventTrigger {

    private final DBService dbService;
    private final BpmnService bpmnService;
    private final ScriptService scriptService;
    private final FlowNavigator flowNavigator;
    private final ElementSupport elementSupport;
    private final TimerJobRepository timerJobRepository;
    private final CancelingPhaseService cancelingPhaseService;

    // WO-REL-17: explicit business zone for cycle re-arm resolution (same as TimerJobExecutor)
    @Value("${zorrobpm.business-timezone:Asia/Almaty}")
    private ZoneId businessZone;

    /**
     * Evaluates a conditional event's FEEL condition against the given variables (a leading {@code =} is stripped).
     */
    public boolean conditionHolds(BpmnElementModel element, List<ProcessVariable> variables) {
        String expression = Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getEventDefinition)
            .map(EventDefinitionExtensionModel::getExpression)
            .filter(s -> !s.isBlank())
            .orElseThrow(() -> new EngineException("Conditional event " + element.getId() + " has no condition"));
        if (expression.startsWith("=")) {
            expression = expression.substring(1);
        }
        Object result = scriptService.evaluateScript(expression, variables);
        return Boolean.TRUE.equals(result);
    }

    /**
     * Finds all conditional boundary events attached to the given host element.
     */
    public List<BpmnElementModel> findConditionalBoundaries(BpmnProcessDefinitionModel bpmn, String hostId) {
        List<BpmnElementModel> boundaries = new ArrayList<>();
        for (BpmnElementModel element : bpmn.getElements()) {
            if (element.getType() != BpmnElementType.CONDITIONAL_BOUNDARY_EVENT) {
                continue;
            }
            String attached = Optional.ofNullable(element.getExtensions())
                .map(BpmnElementExtensionModel::getBoundaryEventExtension)
                .map(BoundaryEventExtensionModel::getAttachedToRef)
                .orElse(null);
            if (hostId.equals(attached)) {
                boundaries.add(element);
            }
        }
        return boundaries;
    }

    /**
     * Fires a boundary event (timer or message) on its host activity. Interrupting boundaries cancel
     * the host and continue the host's token from the boundary; non-interrupting ones leave the host
     * running and spawn a parallel branch on a new token. A no-op if the host already finished.
     *
     * @return {@code true} if the boundary actually fired (host was active), {@code false} if skipped
     */
    public boolean fireBoundary(UUID hostActivityId, String boundaryElementId, List<ProcessVariable> variables,
                         TokenExecutor executor) {
        Activity host = elementSupport.lockAndReload(hostActivityId);
        if (host.getStatus() == ActivityStatus.COMPLETED || host.getStatus() == ActivityStatus.CANCELLED) {
            log.info("Boundary {} fired but host activity {} is {}, ignoring", boundaryElementId, hostActivityId, host.getStatus());
            return false;
        }

        UUID processInstanceId = host.getProcessInstanceId();
        UUID tokenId = host.getToken();

        if (variables != null && !variables.isEmpty()) {
            dbService.setVariables(processInstanceId, variables);
        }

        ProcessInstance processInstance = dbService.getProcessInstance(processInstanceId);
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processInstance.getProcessDefinitionId());
        BpmnElementModel boundary = bpmn.getElement(boundaryElementId);

        boolean interrupting = Optional.ofNullable(boundary.getExtensions())
            .map(BpmnElementExtensionModel::getBoundaryEventExtension)
            .map(BoundaryEventExtensionModel::isInterrupting)
            .orElse(true);

        if (interrupting) {
            // WO-C8-28: snapshot the live user tasks FIRST — only freshly cancelled
            // activities may open a canceling phase (an already-cancelled task whose
            // phase ran and closed must never reopen on a later firing).
            List<UUID> cancelCandidates = cancelingPhaseService.activeUserTaskIdsOnToken(tokenId);
            dbService.cancelActivity(hostActivityId);
            // WO-ENG-3: Cancel all remaining active activities on this token.  For multi-instance,
            // this terminates all sibling MI instances and their inner tasks (they share the same
            // token).  For a single-instance host, the host was already cancelled above so this
            // call is a safe no-op (the host is no longer CREATED/IN_PROGRESS).
            dbService.cancelActiveActivitiesForToken(tokenId);
            // WO-C8-28: canceling listeners defer the cancellation tail — the boundary
            // continuation below runs only after the last canceling listener completes
            // (see the canceling resume branch in CompletionService). Returns true all
            // the same: the boundary fired, cancellation started; only its tail parked.
            // (Callers trigger conditional events on true — they evaluate at fire time,
            // not at continuation time; no retry hinges on this value — timer jobs are
            // one-shot by then, message correlations ignore it.)
            if (cancelingPhaseService.openForActivities(cancelCandidates, boundaryElementId)) {
                return true;
            }
            // WO-ENG-1: An interrupting boundary replaces the host's branch path.  The original branch
            // (e.g. host → join → endEvent) is cancelled; the boundary's continuation (boundary →
            // boundary-end) takes its place.  Decrement the token's pending_branches counter so that
            // the single end-event reachable from the boundary correctly completes the instance when
            // the counter reaches 0.  Without this, a parallel-sibling branch stuck at a join that
            // the host was meant to arrive at would keep the counter > 0 forever.
            Token hostToken = dbService.getToken(tokenId);
            if (hostToken.getPendingBranches() != null && hostToken.getPendingBranches() > 0) {
                dbService.decrementPendingBranches(tokenId);
            }
            log.info("{}/{}: Boundary {} interrupting host {}", processInstanceId, tokenId, boundaryElementId, host.getBpmnElementId());
            flowNavigator.proceedToOutgoing(processInstanceId, tokenId, bpmn, boundary, executor);
        } else {
            Token branch = dbService.createToken(tokenId);
            log.info("{}/{}: Boundary {} firing non-interrupting on host {} (branch token {})", processInstanceId, tokenId, boundaryElementId, host.getBpmnElementId(), branch.getId());
            flowNavigator.proceedToOutgoing(processInstanceId, branch.getId(), bpmn, boundary, executor);
            rearmRepeatingBoundaryTimer(hostActivityId, boundary, processInstanceId);
        }
        return true;
    }

    /**
     * Re-arms a repeating non-interrupting boundary timer after it fires.
     *
     * WO-REL-17 (R-04, same defect class WO-REL-14 fixed for catch/start timers): the re-arm is
     * driven by the PERSISTED state of the fired timer job (remaining count, cycle expression,
     * previous dueAt), never recomputed from the BPMN model:
     * <ul>
     *   <li>remaining &gt; 0 → schedule the next occurrence with {@code remaining - 1};</li>
     *   <li>remaining == 0 → the bounded cycle ({@code R<n>/...}) is exhausted: no re-arm;</li>
     *   <li>remaining == null (with a persisted expression) → unbounded cycle ({@code R/...} or
     *       cron): keep re-arming with {@code null} remaining;</li>
     *   <li>no persisted expression (pre-REL-14/REL-17 row) → end the cycle with a warning
     *       instead of re-arming forever or bursting (same policy as {@code TimerJobExecutor});</li>
     *   <li>no fired job found (e.g. a direct API/test fire without a scheduled job) → end the
     *       cycle with a warning; {@code fireBoundary} is also reachable for signal/message
     *       boundaries, but those are not CYCLE timers so they never reach here.</li>
     * </ul>
     * The next occurrence is computed from the fired job's {@code dueAt} via
     * {@link TimerExpressions#firstOccurrence(String, Instant, ZoneId)}, so execution latency
     * cannot accumulate drift across repetitions.
     */
    private void rearmRepeatingBoundaryTimer(UUID hostActivityId, BpmnElementModel boundary, UUID processInstanceId) {
        if (boundary.getType() != BpmnElementType.BOUNDARY_TIMER_EVENT) {
            return;
        }
        TimerEventExtensionModel timer = Optional.ofNullable(boundary.getExtensions())
            .map(BpmnElementExtensionModel::getTimerEventExtension)
            .orElse(null);
        if (timer == null || timer.getType() != com.zorrodev.bpm.engine.bpmn.model.TimerEventType.CYCLE) {
            return;
        }
        // WO-REL-17: read the state from the fired job, not from the BPMN model. The lookup is
        // scoped by (host activity, boundary element); since WO-PERF-3 the job also carries the
        // processInstanceId (used by retention cleanup), see createTimerJob(...).
        Optional<TimerJobEntity> firedJobRef = timerJobRepository
            .findFirstByActivityIdAndBoundaryElementIdAndFiredTrueOrderByCreatedAtDesc(hostActivityId, boundary.getId());
        TimerJobEntity firedJob = (firedJobRef == null || firedJobRef.isEmpty()) ? null : firedJobRef.get();
        if (firedJob == null) {
            log.warn("Boundary timer {} on host activity {} fired without a matching timer job — ending cycle without re-arm",
                boundary.getId(), hostActivityId);
            return;
        }
        String expression = firedJob.getExpression();
        if (expression == null) {
            // Pre-WO-REL-14/REL-17 row with no persisted expression: cannot safely resolve the
            // real interval, so end the cycle here rather than burst or repeat forever.
            log.warn("Boundary timer job {} ({} on {}) has no persisted expression — ending cycle instead of re-arming",
                firedJob.getId(), boundary.getId(), hostActivityId);
            return;
        }
        Integer remaining = firedJob.getRemainingCount();
        if (remaining != null && remaining <= 0) {
            log.info("Boundary timer {} on host activity {} exhausted (remaining={}) — no re-arm",
                boundary.getId(), hostActivityId, remaining);
            return;
        }
        Instant next = TimerExpressions.firstOccurrence(expression, firedJob.getDueAt(), businessZone);
        Integer nextRemaining = remaining == null ? null : remaining - 1;
        dbService.createTimerJob(hostActivityId, next, boundary.getId(), nextRemaining, expression, processInstanceId);
        log.info("{}/{}: Re-arming boundary timer {} on host {} for {} (remaining={}, expression={})",
            processInstanceId, hostActivityId, boundary.getId(), hostActivityId, next, nextRemaining, expression);
    }

    /**
     * Starts a message-triggered event sub-process within {@code processInstanceId}. An interrupting event
     * sub-process consumes the subscription, cancels the instance's active activities (the main flow) and
     * runs the handler on a fresh token so its end event completes the instance. A non-interrupting one
     * keeps the subscription and the main flow, running the handler in its own subprocess scope (its end
     * completes only the scope). A no-op if the instance has already completed.
     *
     * @return {@code true} if the event sub-process was interrupting, {@code false} otherwise
     */
    public boolean triggerEventSubprocess(UUID processInstanceId, String eventSubprocessId,
                                   List<ProcessVariable> variables, TokenExecutor executor) {
        dbService.lockProcessInstance(processInstanceId);
        ProcessInstance pi = dbService.getProcessInstance(processInstanceId);
        if (pi.getCompletedAt() != null) {
            log.info("{}: Event sub-process {} trigger ignored, instance already completed", processInstanceId, eventSubprocessId);
            return false;
        }
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(pi.getProcessDefinitionId());
        BpmnElementModel eventSubProcess = bpmn.getElement(eventSubprocessId);
        SubProcessExtensionModel ext = Optional.ofNullable(eventSubProcess.getExtensions())
            .map(BpmnElementExtensionModel::getSubProcessExtension)
            .orElseThrow(() -> new EngineException("Event sub-process " + eventSubprocessId + " has no metadata"));

        if (variables != null && !variables.isEmpty()) {
            dbService.setVariables(processInstanceId, variables);
        }

        if (ext.isInterrupting()) {
            dbService.cancelActiveActivities(processInstanceId);
            Token token = dbService.createToken(null);
            log.info("{}/{}: Interrupting event sub-process {} starting at {}", processInstanceId, token.getId(), eventSubprocessId, ext.getStartEventId());
            executor.execute(processInstanceId, token.getId(), ext.getStartEventId());
            return true;
        }
        Token branchToken = dbService.createToken(null);
        UUID containerActivityId = dbService.createActivity(processInstanceId, branchToken.getId(), eventSubProcess);
        Token scopeToken = dbService.createToken(branchToken.getId(), containerActivityId);
        log.info("{}/{}: Non-interrupting event sub-process {} starting at {} (scope {})", processInstanceId, scopeToken.getId(), eventSubprocessId, ext.getStartEventId(), containerActivityId);
        executor.execute(processInstanceId, scopeToken.getId(), ext.getStartEventId());
        return false;
    }

    /**
     * WO-SEC-59 #2: determine whether the referenced event sub-process is interrupting WITHOUT firing it.
     * Used to decide whether a signal/message correlation may consume the subscription (interrupting
     * handlers must fire exactly once; non-interrupting ones re-fire and keep listening).
     */
    public boolean isInterruptingEventSubprocess(UUID processInstanceId, String eventSubprocessId) {
        ProcessInstance pi = dbService.getProcessInstance(processInstanceId);
        if (pi == null || pi.getCompletedAt() != null) {
            return false;
        }
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(pi.getProcessDefinitionId());
        BpmnElementModel esp = bpmn.getElement(eventSubprocessId);
        return Optional.ofNullable(esp.getExtensions())
                .map(BpmnElementExtensionModel::getSubProcessExtension)
                .map(SubProcessExtensionModel::isInterrupting)
                .orElse(false);
    }

    /**
     * Starts a process instance beginning at a specific start element (used by message/timer start
     * events, which begin at their own start node rather than the plain start).
     */
    public UUID startProcessInstanceAt(UUID parentActivityId, UUID processDefinitionId, String startEventId,
                                List<ProcessVariable> variables, TokenExecutor executor) {
        return startProcessInstanceAt(parentActivityId, processDefinitionId, startEventId, variables, null, executor);
    }

    /**
     * WO-API-1 (API-7): перегрузка с initiator — create несёт его в том же
     * INSERT (атомарно со стартом). Null = старый путь побайтово.
     */
    public UUID startProcessInstanceAt(UUID parentActivityId, UUID processDefinitionId, String startEventId,
                                List<ProcessVariable> variables, String claimedInitiator, TokenExecutor executor) {
        UUID processInstanceId = dbService.createProcessInstance(
            parentActivityId, processDefinitionId, variables, claimedInitiator);

        // WO-REL-31 CR-2: NO second variable write here. createProcessInstance already INSERTs the
        // initial variables; a redundant find-then-UPDATE (one SELECT per variable, multi-instance
        // start excluded) left a stale {name:"update"} in the thread-local change map — an unrelated
        // later variable change matched an update-only conditionalFilter on that stale record and
        // spuriously re-evaluated the condition.

        UUID parentTokenId = null;
        if (parentActivityId != null) {
            Activity activity = dbService.getActivity(parentActivityId);
            Token token = dbService.getToken(activity.getToken());
            parentTokenId = token.getId();
        }
        Token token = dbService.createToken(parentTokenId);

        subscribeEventSubprocesses(processInstanceId, processDefinitionId);

        executor.execute(processInstanceId, token.getId(), startEventId);

        return processInstanceId;
    }

    /**
     * Registers an instance-scoped message/signal/timer subscription for every triggered event sub-process
     * in the definition.
     */
    private void subscribeEventSubprocesses(UUID processInstanceId, UUID processDefinitionId) {
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processDefinitionId);
        for (BpmnElementModel element : bpmn.getEventSubProcesses()) {
            SubProcessExtensionModel ext = Optional.ofNullable(element.getExtensions())
                .map(BpmnElementExtensionModel::getSubProcessExtension)
                .orElse(null);
            if (ext == null) {
                continue;
            }
            if (ext.getTriggerMessageName() != null) {
                dbService.createEventSubprocessMessageSubscription(processInstanceId, ext.getTriggerMessageName(), element.getId());
                log.info("{}: Event sub-process {} subscribed to message '{}'", processInstanceId, element.getId(), ext.getTriggerMessageName());
            } else if (ext.getTriggerSignalName() != null) {
                dbService.createEventSubprocessSignalSubscription(processInstanceId, ext.getTriggerSignalName(), element.getId());
                log.info("{}: Event sub-process {} subscribed to signal '{}'", processInstanceId, element.getId(), ext.getTriggerSignalName());
            } else if (ext.getTriggerTimer() != null) {
                Instant dueAt = elementSupport.computeDueAt(ext.getTriggerTimer(), element.getId(), processInstanceId);
                dbService.createEventSubprocessTimerJob(processInstanceId, dueAt, element.getId());
                log.info("{}: Event sub-process {} scheduled timer for {}", processInstanceId, element.getId(), dueAt);
            }
        }
    }

    /**
     * Broadcasts a signal: wakes every active subscription with the matching name, across process
     * instances (1:N). Each waiting activity is signalled under its own per-instance lock, so
     * concurrent broadcasts/correlations stay isolated per instance.
     *
     * <p><b>WO-REL-31 CR-3:</b> subscriptions are fetched in keyset-paged batches (≤500 rows per
     * page, id-DESC order). This prevents OOM on wide fan-outs and gives deterministic ordering.</p>
     *
     * @param signalFn callback to signal a waiting activity (avoids circular dependency with CompletionService)
     */
    public void broadcastSignal(String signalName, List<ProcessVariable> variables, TokenExecutor executor,
                                java.util.function.BiConsumer<UUID, List<ProcessVariable>> signalFn) {
        List<com.zorrodev.bpm.engine.dto.SignalStartSubscription> startSubscriptions =
            dbService.findSignalStartSubscriptions(signalName);

        if (!startSubscriptions.isEmpty()) {
            for (com.zorrodev.bpm.engine.dto.SignalStartSubscription start : startSubscriptions) {
                log.info("Signal '{}' starting a new instance of {} at {}", signalName, start.getProcessDefinitionId(), start.getElementId());
                startProcessInstanceAt(null, start.getProcessDefinitionId(), start.getElementId(), variables, executor);
            }
        }

        // WO-REL-31 CR-3: keyset-paged fan-out (same strategy as correlateMessage)
        boolean sawSubscriptions = false;
        UUID cursor = null;
        while (true) {
            List<SignalSubscription> page = dbService.findSignalSubscriptions(signalName, cursor);
            if (page.isEmpty()) {
                break;
            }
            sawSubscriptions = true;
            UUID nextCursor = page.get(page.size() - 1).getId(); // min id in this page
            for (SignalSubscription subscription : page) {
                if (subscription.getEventSubprocessId() != null) {
                    boolean interrupting = isInterruptingEventSubprocess(subscription.getProcessInstanceId(), subscription.getEventSubprocessId());
                    boolean shouldFire = interrupting ? dbService.consumeSignalSubscription(subscription.getId()) : true;
                    if (shouldFire) {
                        log.info("Broadcasting signal '{}' to event sub-process {} on instance {}", signalName, subscription.getEventSubprocessId(), subscription.getProcessInstanceId());
                        triggerEventSubprocess(subscription.getProcessInstanceId(), subscription.getEventSubprocessId(), variables, executor);
                    }
                    continue;
                }
                if (dbService.consumeSignalSubscription(subscription.getId())) {
                    if (subscription.getBoundaryElementId() != null) {
                        log.info("Broadcasting signal '{}' to boundary {} on instance {} activity {}", signalName, subscription.getBoundaryElementId(), subscription.getProcessInstanceId(), subscription.getActivityId());
                        fireBoundary(subscription.getActivityId(), subscription.getBoundaryElementId(), variables, executor);
                    } else {
                        log.info("Broadcasting signal '{}' to instance {} activity {}", signalName, subscription.getProcessInstanceId(), subscription.getActivityId());
                        signalFn.accept(subscription.getActivityId(), variables);
                    }
                }
            }
            if (page.size() < DBService.FAN_OUT_BATCH_SIZE) {
                break;
            }
            cursor = nextCursor;
        }
        if (!sawSubscriptions && startSubscriptions.isEmpty()) {
            log.info("No active subscription for signal '{}'", signalName);
        }
    }

}
