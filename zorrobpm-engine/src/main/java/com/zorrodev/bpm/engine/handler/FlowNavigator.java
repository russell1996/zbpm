package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.bpmn.model.*;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ScriptService;
import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Navigation core extracted from ActivityServiceImpl.
 * Handles sequence-flow processing and outgoing-flow traversal.
 * Dispatches element execution via the {@link TokenExecutor} port from {@link ExecutionCtx}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlowNavigator {

    private final DBService dbService;
    private final BpmnService bpmnService;
    private final ScriptService scriptService;
    private final ElementSupport elementSupport;
    // WO-C8-32: JSON codec for the ad-hoc activated-set variable (same mapper MI uses).
    private final tools.jackson.databind.ObjectMapper objectMapper;
    // WO-C8-33: scope-job (re)issue on inner completions (job-worker mode only).
    private final ServiceTaskEnqueueService serviceTaskEnqueueService;

    /**
     * Follows every outgoing sequence flow of {@code element} unconditionally and executes the
     * target of each. Shared "continue from here" step used by start events, completed tasks,
     * signalled wait states and parent continuation after a subprocess/call activity ends.
     *
     * WO-QW-1 Q-7: history kept to 2 lines + WO ref — the full ad-hoc HOLD story lives in
     * governance/reports/WO-C8-32.md.
     */
    public void proceedToOutgoing(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel element, TokenExecutor executor) {
        // WO-C8-32 (+HOLD): middles only RECORD arrival, count evaluated at chain ends + quiescence (WO-C8-32.md).
        // Returns SCOPE_FINISHED only when a scope finished and consumed the branch.
        if (handleAdHocArrival(processInstanceId, tokenId, bpmn, element, executor) == ArrivalOutcome.SCOPE_FINISHED) {
            return;
        }
        if (element.getOutgoing() == null) {
            return; // a dead end (e.g. a compensation handler off the main flow has no outgoing flow)
        }
        // WO-ENG-12: implicit fork — an element with 2+ outgoing without an explicit gateway is an
        // AND-split in execution terms. Without a pendingBranches counter the first branch to finish
        // would immediately complete the instance (decrementPendingBranches → -1 → "linear, complete").
        // Apply the same durable-counter scheme as ParallelGatewayHandler (create child token, set
        // pendingBranches before any branch starts) so finishBranch waits for all branches.
        // Gateways have their own handlers and must not be treated as implicit forks (e.g.
        // event-based gateway with 2 outgoing is an XOR, not an AND — first event win cancels the other).
        if (element.getOutgoing().size() > 1 && !isGateway(element.getType())) {
            Token newToken = dbService.createToken(tokenId);
            UUID newTokenId = newToken.getId();
            dbService.setPendingBranches(newTokenId, element.getOutgoing().size());
            for (String outgoing : element.getOutgoing()) {
                processFlow(processInstanceId, newTokenId, outgoing, false, null);
                BpmnFlowModel flow = bpmn.getFlow(outgoing);
                if (flow == null) {
                    throw new IllegalStateException("Sequence flow '" + outgoing + "' not found in the process definition");
                }
                BpmnElementModel target = bpmn.getElement(flow.getTargetRef());
                if (target == null) {
                    throw new IllegalStateException("Target element '" + flow.getTargetRef() + "' of sequence flow '" + outgoing + "' not found in the process definition");
                }
                executor.execute(processInstanceId, newTokenId, bpmn, target);
            }
            return;
        }
        for (String outgoing : element.getOutgoing()) {
            processFlow(processInstanceId, tokenId, outgoing, false, null);
            BpmnFlowModel flow = bpmn.getFlow(outgoing);
            if (flow == null) {
                throw new IllegalStateException("Sequence flow '" + outgoing + "' not found in the process definition");
            }
            BpmnElementModel target = bpmn.getElement(flow.getTargetRef());
            if (target == null) {
                throw new IllegalStateException("Target element '" + flow.getTargetRef() + "' of sequence flow '" + outgoing + "' not found in the process definition");
            }
            executor.execute(processInstanceId, tokenId, bpmn, target);
        }
    }

    private boolean isGateway(BpmnElementType type) {
        return type != null && type.name().endsWith("_GATEWAY");
    }

    /**
     * WO-C8-32: ad-hoc subprocess join arrival (internal mode) — the "new separate method
     * next to proceedToOutgoing" from the design. Fires when an element of an activated set
     * completes on a token that carries a live ad-hoc scope; ordinary completions (no ad-hoc
     * scope on this token, or the element was never activated) fall through untouched.
     * <p>
     * HOLD-fix (2026-09-07, живой дефект CTO): прибытие корня пишется в момент его
     * завершения, но подсчёт {@code arrived >= expected} оценивается ТОЛЬКО в тупике
     * цепочки. Серединка (есть исходящие) обязана сначала протечь дальше обычным путём —
     * иначе скоуп из одного корня с исходящим на внутренний элемент завершался бы до
     * того, как цепочка выполнилась (taskChain не создавался вообще). Серединка лишь
     * проверяет condition (раннее завершение с cancel-семантикой); тупик проверяет
     * condition ИЛИ (счётчик + тишина на токене — цепочка корня действительно дошла).
     * <p>
     * Per arrival, in order: output aggregation (mirror of MI, per completed inner flow),
     * arrival record on the scope's generic-counter key, completion test (see above; the
     * FEEL {@code completionCondition} is evaluated against the live root variables —
     * the completing tail already merged its outputs there). Unfinished scopes leave the
     * token alone; a finished scope completes its container activity, cancels the
     * token-mates (unless {@code cancelRemainingInstances} is false) and continues past
     * the ad-hoc element.
     *
     * @return {@link ArrivalOutcome#SCOPE_FINISHED} when a scope finished here and
     *         consumed this branch (caller must return WITHOUT flowing the completing
     *         element's own outgoing — the scope took over), {@link ArrivalOutcome#CONTINUE}
     *         otherwise. Full HOLD story: governance/reports/WO-C8-32.md.
     */
    public ArrivalOutcome handleAdHocArrival(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn,
            BpmnElementModel element, TokenExecutor executor) {
        // One indexed read of the instance's active activities per proceedToOutgoing call
        // (created/in-progress only — usually a handful of rows). No new DB surface: the
        // repository method already exists (cancel paths use it).
        // WO-REL-31 CR-4: this single read replaces the former re-reads inside
        // evaluateScopeDone/finishAdHocScope ("getActive on every proceed again").
        // Cancelled ids are tracked in {@code cancelled} so the snapshot stays exactly
        // equivalent to a fresh query (cancelled rows must disappear from later views).
        List<Activity> active = dbService.getActiveActivities(processInstanceId);
        Set<UUID> cancelled = new HashSet<>();
        List<Activity> scopes = active.stream()
            .filter(a -> tokenId.equals(a.getToken()) && a.getType() == BpmnElementType.AD_HOC_SUB_PROCESS)
            .toList();
        if (scopes.isEmpty()) {
            return ArrivalOutcome.CONTINUE;
        }
        boolean isChainMiddle = element.getOutgoing() != null && !element.getOutgoing().isEmpty();
        ArrivalOutcome outcome = ArrivalOutcome.CONTINUE;
        for (Activity scope : scopes) {
            AdHocJoin.ScopeState state = AdHocJoin.resolve(dbService, objectMapper, processInstanceId, scope.getId());
            if (isJobModeScope(bpmn, scope)) {
                // WO-C8-33: job-worker mode — the worker decides, never the counter.
                // No arrivals, no count/condition evaluation here (the native
                // completionCondition is worker-owned via isCompletionConditionFulfilled).
                // Aggregate per activated completion like internal mode, and recreate the
                // scope job whenever a chain settles (dead end = "a flow completed").
                // Never consumes: the flow always continues (middles) or ends (dead ends).
                // (Scope element is non-null here — isJobModeScope just resolved it.)
                if (state != null && state.activatedIds().contains(element.getId())) {
                    aggregateAdHocOutput(processInstanceId, bpmn, scope);
                }
                if (!isChainMiddle) {
                    AdHocJoin.issueScopeJob(dbService, elementSupport, serviceTaskEnqueueService,
                        processInstanceId, scope.getId(), bpmn.getElement(scope.getBpmnElementId()));
                    log.info("{}/{}: Ad-hoc subprocess {} job-worker mode: inner flow settled, scope job recreated",
                        processInstanceId, tokenId, scope.getBpmnElementId());
                }
                continue;
            }
            if (state == null || !state.activatedIds().contains(element.getId())) {
                if (!isChainMiddle) {
                    // Chain end of some activated root (or an unrelated dead end on this token):
                    // the chain settled — evaluate even without an arrival of its own.
                    if (evaluateScopeDone(processInstanceId, bpmn, scope, state, active, cancelled)) {
                        finishAdHocScope0(processInstanceId, tokenId, bpmn, scope,
                            state == null ? null : AdHocJoin.joinKey(scope.getId(), state.batchUuid()), executor, null,
                            active, cancelled);
                        outcome = ArrivalOutcome.SCOPE_FINISHED;
                    }
                }
                continue;
            }
            aggregateAdHocOutput(processInstanceId, bpmn, scope);
            String key = AdHocJoin.joinKey(scope.getId(), state.batchUuid());
            dbService.recordParallelGatewayArrival(processInstanceId, key, AdHocJoin.arrivalMarker(element.getId()));
            if (isChainMiddle) {
                // The root's own step is done, but its chain is not — flow on, evaluate
                // only an early completionCondition (whose cancel semantics covers the
                // not-yet-created downstream: the scope takes over, the chain never runs).
                if (adHocConditionMet(processInstanceId, bpmn, scope)) {
                    finishAdHocScope0(processInstanceId, tokenId, bpmn, scope, key, executor, null, active, cancelled);
                    outcome = ArrivalOutcome.SCOPE_FINISHED;
                } else {
                    log.info("{}/{}: Ad-hoc subprocess {} root {} done, chain continues",
                        processInstanceId, tokenId, scope.getBpmnElementId(), element.getId());
                }
                continue;
            }
            if (evaluateScopeDone(processInstanceId, bpmn, scope, state, active, cancelled)) {
                finishAdHocScope0(processInstanceId, tokenId, bpmn, scope, key, executor, null, active, cancelled);
                outcome = ArrivalOutcome.SCOPE_FINISHED;
            } else {
                Integer expected = dbService.getInclusiveExpected(processInstanceId, key);
                int arrived = dbService.getParallelGatewayArrivedFlows(processInstanceId, key).size();
                log.info("{}/{}: Ad-hoc subprocess {} not ready: {} of {} inner flows done",
                    processInstanceId, tokenId, scope.getBpmnElementId(), arrived, expected);
            }
        }
        return outcome;
    }

    /**
     * WO-C8-32 (HOLD-fix): full done-test for a settled chain end — condition, or the
     * counter plus quiescence. Null state (parked scope without bookkeeping) never finishes.
     * WO-REL-31 CR-4: quiescence reads the traversal snapshot (minus activities cancelled
     * during this same traversal), not a fresh query — equivalent by construction.
     */
    private boolean evaluateScopeDone(UUID processInstanceId, BpmnProcessDefinitionModel bpmn,
            Activity scope, AdHocJoin.ScopeState state, List<Activity> active, Set<UUID> cancelled) {
        if (state == null) {
            return false;
        }
        if (adHocConditionMet(processInstanceId, bpmn, scope)) {
            return true;
        }
        String key = AdHocJoin.joinKey(scope.getId(), state.batchUuid());
        Integer expected = dbService.getInclusiveExpected(processInstanceId, key);
        int arrived = dbService.getParallelGatewayArrivedFlows(processInstanceId, key).size();
        if (expected == null || arrived < expected) {
            return false;
        }
        // Quiescence: nothing unfinished left on the shared token besides ad-hoc containers
        // (the completing element itself is already COMPLETED — its tail ran before this
        // hook). Evaluated ONLY here, never on a middle: a middle's own downstream is not
        // created yet at hook time, so "quiet" would lie for it.
        return active.stream()
            .filter(a -> !cancelled.contains(a.getId()))
            .noneMatch(a -> scope.getToken() != null && scope.getToken().equals(a.getToken())
                && a.getType() != BpmnElementType.AD_HOC_SUB_PROCESS);
    }

    /**
     * WO-C8-33: true when the scope element carries a {@code zeebe:taskDefinition}
     * (job-worker mode). Same predicate shape as the job-based-event check (non-blank
     * resolved job type), read off the shared service-task extension the parser
     * attaches — no new state.
     */
    private boolean isJobModeScope(BpmnProcessDefinitionModel bpmn, Activity scope) {
        BpmnElementModel scopeElement = bpmn.getElement(scope.getBpmnElementId());
        if (scopeElement == null) {
            return false;
        }
        String job = elementSupport.serviceTaskJob(scopeElement);
        return job != null && !job.isBlank();
    }

    /** WO-C8-32: per-completion output aggregation — MI's append step on the ad-hoc trigger. */
    private void aggregateAdHocOutput(UUID processInstanceId, BpmnProcessDefinitionModel bpmn, Activity scope) {
        BpmnElementModel scopeElement = bpmn.getElement(scope.getBpmnElementId());
        AdHocSubProcessExtensionModel ext = Optional.ofNullable(scopeElement)
            .map(BpmnElementModel::getExtensions)
            .map(BpmnElementExtensionModel::getAdHocSubProcessExtension)
            .orElse(null);
        if (ext == null || ext.getOutputCollection() == null || ext.getOutputCollection().isBlank()
            || ext.getOutputElement() == null || ext.getOutputElement().isBlank()) {
            return;
        }
        // Root variables: every completion tail merges its outputs to root BEFORE reaching
        // this hook (and drops its scoped rows), so root is exactly "the completed flow's
        // output" here. Written root-scoped like MI (shared-token engine has no child scope;
        // the value is therefore visible outside the ad-hoc when it finishes).
        Object value = scriptService.evaluateExpression(ext.getOutputElement(), dbService.getVariables(processInstanceId));
        elementSupport.appendToJsonList(processInstanceId, ext.getOutputCollection(), value);
    }

    /** WO-C8-32: FEEL completionCondition against the live root variables (raw, '=' kept). */
    private boolean adHocConditionMet(UUID processInstanceId, BpmnProcessDefinitionModel bpmn, Activity scope) {
        BpmnElementModel scopeElement = bpmn.getElement(scope.getBpmnElementId());
        String expression = Optional.ofNullable(scopeElement)
            .map(BpmnElementModel::getExtensions)
            .map(BpmnElementExtensionModel::getAdHocSubProcessExtension)
            .map(AdHocSubProcessExtensionModel::getCompletionCondition)
            .orElse(null);
        if (expression == null || expression.isBlank()) {
            return false;
        }
        // Defensive '=' strip mirroring MultiInstanceExecutor.completionConditionMet (the
        // parser already strips; this only guards hand-built models in tests).
        String feel = expression.strip();
        if (feel.startsWith("=")) {
            feel = feel.substring(1);
        }
        Object result = scriptService.evaluateExpression(feel, dbService.getVariables(processInstanceId));
        return Boolean.TRUE.equals(result);
    }

    /**
     * WO-C8-32: scope-done tail — clear bookkeeping, complete the container, cancel the
     * rest, continue. Public for the WO-C8-33 worker-driven finish (same tail, the worker
     * only supplies the decision + its own cancel flag): fresh read of active activities,
     * standalone traversal.
     *
     * @param cancelRemainingOverride WO-C8-33 worker flag; null = resolve the BPMN
     *        {@code cancelRemainingInstances} attribute (docs default true), as internal mode does
     */
    public void finishAdHocScope(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn,
            Activity scope, String key, TokenExecutor executor, Boolean cancelRemainingOverride) {
        finishAdHocScope0(processInstanceId, tokenId, bpmn, scope, key, executor,
            cancelRemainingOverride, null, new HashSet<>());
    }

    /**
     * WO-REL-31 CR-4: core shared by the worker path (fresh snapshot, {@code activeSnapshot}
     * null) and the internal traversal (single hoisted snapshot + cancelled-id tracking, so
     * the mate list stays exactly what a fresh query would return).
     */
    private void finishAdHocScope0(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn,
            Activity scope, String key, TokenExecutor executor, Boolean cancelRemainingOverride,
            List<Activity> activeSnapshot, Set<UUID> cancelled) {
        if (key != null) {
            dbService.clearParallelGatewayArrivals(processInstanceId, key);
        }
        dbService.completeActivity(scope.getId());
        BpmnElementModel scopeElement = bpmn.getElement(scope.getBpmnElementId());
        boolean cancelRest = cancelRemainingOverride != null ? cancelRemainingOverride : Optional.ofNullable(scopeElement)
            .map(BpmnElementModel::getExtensions)
            .map(BpmnElementExtensionModel::getAdHocSubProcessExtension)
            .map(AdHocSubProcessExtensionModel::getCancelRemainingInstances)
            .map(cancel -> !Boolean.FALSE.equals(cancel))
            .orElse(true);
        if (cancelRest) {
            // The container activity is already COMPLETED above, so it is excluded; other
            // AD_HOC scope activities on this token are spared too (a nested ad-hoc that
            // finishes must not wipe its outer scope — only the token-mate elements go).
            // Approximation, documented in the WO-C8-32 report: an outer scope's unfinished
            // non-container siblings share this token and are cancelled as well.
            List<Activity> active = activeSnapshot != null
                ? activeSnapshot : dbService.getActiveActivities(processInstanceId);
            List<Activity> mates = active.stream()
                .filter(a -> !cancelled.contains(a.getId())
                    && tokenId.equals(a.getToken()) && a.getType() != BpmnElementType.AD_HOC_SUB_PROCESS)
                .toList();
            for (Activity mate : mates) {
                dbService.cancelActivity(mate.getId());
                cancelled.add(mate.getId());
            }
        }
        log.info("{}/{}: Completing {}: {}/{} (cancelRemaining={})", processInstanceId, tokenId,
            scope.getType(), scope.getId(), scope.getBpmnElementId(), cancelRest);
        proceedToOutgoing(processInstanceId, tokenId, bpmn, scopeElement, executor);
    }

    /**
     * Processes a single sequence flow: evaluates its condition (if any), creates the flow
     * activity, and records gateway arrivals for parallel/inclusive joins.
     *
     * @return the created flow activity ID, or null if the flow was not taken (condition false)
     */
    public UUID processFlow(@NonNull UUID processInstanceId, @NonNull UUID tokenId, String flowId, @NonNull Boolean processExpression, Boolean defaultFlow) {
        return processFlow(processInstanceId, tokenId, flowId, processExpression, defaultFlow, null);
    }

    public UUID processFlow(@NonNull UUID processInstanceId, @NonNull UUID tokenId, String flowId, @NonNull Boolean processExpression, Boolean defaultFlow, List<ProcessVariable> cachedVariables) {
        ProcessInstance processInstance = dbService.getProcessInstance(processInstanceId);
        UUID processDefinitionId = processInstance.getProcessDefinitionId();

        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processDefinitionId);
        BpmnFlowModel flow = bpmn.getFlow(flowId);
        if (flow == null) {
            throw new IllegalStateException("Sequence flow '" + flowId + "' not found in the process definition");
        }
        String targetRef = flow.getTargetRef();
        String sourceRef = flow.getSourceRef();
        BpmnElementModel target = bpmn.getElement(targetRef);
        BpmnElementModel source = bpmn.getElement(sourceRef);
        if (target == null) {
            throw new IllegalStateException("Target element '" + targetRef + "' of sequence flow '" + flowId + "' not found in the process definition");
        }

        UUID flowActivityId = null;

        if (processExpression) {
            String expression = Optional.ofNullable(flow)
                .map(BpmnFlowModel::getConditionExpression)
                .map(BpmnConditionExpressionModel::getExpression)
                .filter(str -> !str.isEmpty())
                .map(str -> str.substring(1))
                .orElse(null);
            if (!(Objects.isNull(expression) && defaultFlow)) {
                List<ProcessVariable> variables = cachedVariables != null ? cachedVariables : dbService.getVariables(processInstanceId);
                Boolean test = (Boolean) scriptService.evaluateScript(expression, variables);
                if (Boolean.TRUE.equals(test)) {
                    flowActivityId = dbService.createActivity(processInstanceId, tokenId, flow);
                }
            }
        } else {
            flowActivityId = dbService.createActivity(processInstanceId, tokenId, flow);
        }

        if (flowActivityId != null) {
            dbService.completeActivity(flowActivityId);
            log.info("{}/{}: Flow: {}/{} => from {}/{} to {}/{}", processInstanceId, tokenId, flowActivityId, flowId, source.getType(), source.getId(), target.getType(), target.getId());

            // Arriving at a parallel- or inclusive-gateway join: record this incoming flow so the join
            // can tell when every (activated) branch has arrived.
            if ((target.getType() == BpmnElementType.PARALLEL_GATEWAY || target.getType() == BpmnElementType.INCLUSIVE_GATEWAY)
                    && target.getIncoming().size() > 1) {
                dbService.recordParallelGatewayArrival(processInstanceId, target.getId(), flowId);
            }
        }

        return flowActivityId;
    }

    /**
     * Ends the current branch at an end event: if the token is inside an embedded subprocess scope,
     * completes the container and continues the parent token from the subprocess's outgoing flows;
     * otherwise consumes the current token and checks whether any active activities remain in the
     * instance. Only completes the process instance when no other active tokens/branches remain.
     * Shared by plain and escalation end events.
     */
    public void finishBranch(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, TokenExecutor executor) {
        // WO-REL-30 (B-4): a stale token reference (cancel/cleanup races) is a
        // managed no-op — log + return, never NoSuchElementException → 500.
        Token endToken = dbService.findToken(tokenId).orElse(null);
        if (endToken == null) {
            log.warn("{}/{}: finishBranch on missing token — stale reference, ignoring", processInstanceId, tokenId);
            return;
        }
        if (endToken.getScopeActivityId() != null) {
            // end of an embedded subprocess scope: complete the container and continue the parent
            // token from the subprocess's outgoing flows; the process instance stays running
            UUID subProcessActivityId = endToken.getScopeActivityId();
            dbService.completeActivity(subProcessActivityId);
            Activity subProcessActivity = dbService.getActivity(subProcessActivityId);
            BpmnElementModel subProcessElement = bpmn.getElement(subProcessActivity.getBpmnElementId());
            UUID parentTokenId = endToken.getParentId();
            log.info("{}/{}: Completing {}: {}/{}", processInstanceId, parentTokenId, subProcessElement.getType(), subProcessActivityId, subProcessElement.getId());
            proceedToOutgoing(processInstanceId, parentTokenId, bpmn, subProcessElement, executor);
            return;
        }

        // WO-ENG-1 (durable, DB-backed counter): decrement the pending-branch counter.
        // - Returns -1 → token has no counter (linear process) → complete immediately.
        // - Returns >0 → other branches still active → stay RUNNING.
        // - Returns 0 → all branches consumed → complete instance.
        //
        // WO-ENG-6: A child token (parentId != null) with no pendingBranches counter was created
        // by a non-interrupting boundary event or event subprocess. Such tokens are linear side-
        // branches that must NOT complete the process instance — only the root token controls
        // instance lifecycle. Without this check, a non-interrupting boundary timer that fires
        // and reaches an end event prematurely completes the instance while the main flow's
        // activities are still open (PROD-REPORT c8aaa9e4, premature completion with open utExecute).
        // CRITICAL: Check that this is a ROOT process instance (parentActivityId == null) —
        // child process instances started by call activities have parentActivityId != null
        // and their tokens DO have parentId set, but must still complete the child instance
        // normally so the call activity can proceed in the parent.
        boolean isChildLinearBranch = false;
        if (endToken != null && endToken.getParentId() != null && endToken.getPendingBranches() == null) {
            ProcessInstance pi = dbService.getProcessInstance(processInstanceId);
            if (pi.getParentActivityId() == null) {
                isChildLinearBranch = true;
            }
        }
        if (isChildLinearBranch) {
            log.info("{}/{}: Child linear token ending — branch complete, instance continues",
                processInstanceId, tokenId);
            return;
        }

        // WO-ENG-13: recursive bubble-up across the full parent-token chain.
        //
        // When a token's pendingBranches reaches 0, it may have a parent token that is itself an
        // implicit fork (its pendingBranches was only decremented *through* this bubble-up, not via a
        // direct finishBranch call on it). We must walk up the chain: if the parent still has pending
        // branches, move up to the parent and re-check; only complete the instance when we reach a
        // token with no parent (root) or a parent with no pending branches of its own.
        //
        // This handles arbitrary nesting depth (a degenerate parallelGateway 1-in/1-out nested inside
        // a fork, nested inside another fork, etc.). Without the loop a degenerate gateway that is the
        // second level deep exhausts its immediate parent but never propagates to the grandparent,
        // leaving the instance hung forever (silently, with no incident).
        UUID currentTokenId = tokenId;
        while (true) {
            int remaining = dbService.decrementPendingBranches(currentTokenId);
            if (remaining == -1) {
                log.info("{}/{}: Linear token (no pending branches), completing instance", processInstanceId, currentTokenId);
                break;
            }
            if (remaining > 0) {
                log.info("{}/{}: {} branch(es) still pending — instance stays RUNNING",
                    processInstanceId, currentTokenId, remaining);
                return;
            }
            // remaining == 0 — all branches of currentToken consumed. Bubble up to parent if it is
            // itself still waiting on sibling branches.
            // WO-REL-30 (B-4): a token deleted mid-flight (cancel/cleanup races)
            // ends the walk as fully consumed — never NoSuchElementException → 500.
            Token currentToken = dbService.findToken(currentTokenId).orElse(null);
            if (currentToken == null) {
                log.warn("{}/{}: bubble-up token gone mid-flight — treating branch as consumed",
                    processInstanceId, currentTokenId);
                break;
            }
            if (currentToken.getParentId() != null) {
                Token parentToken = dbService.findToken(currentToken.getParentId()).orElse(null);
                if (parentToken != null && parentToken.getPendingBranches() != null && parentToken.getPendingBranches() > 0) {
                    log.info("{}/{}: Nested token bubble-up — moving up to parent {}/{} (pending={})",
                        processInstanceId, currentTokenId, currentToken.getParentId(), parentToken.getId(), parentToken.getPendingBranches());
                    currentTokenId = currentToken.getParentId();
                    continue;
                }
            }
            log.info("{}/{}: All branches consumed, completing instance", processInstanceId, currentTokenId);
            break;
        }

        dbService.completeProcessInstance(processInstanceId);

        ProcessInstance pi = dbService.getProcessInstance(processInstanceId);
        UUID parentActivityId = pi.getParentActivityId();
        if (parentActivityId != null) {
            // a call activity finished: continuation mutates the *parent* instance, so lock it
            // (consistent child→parent ordering keeps this deadlock-free) before advancing it
            Activity parentActivity = dbService.getActivity(parentActivityId);
            dbService.lockProcessInstance(parentActivity.getProcessInstanceId());
            dbService.completeActivity(parentActivityId);

            UUID parentProcessInstanceId = parentActivity.getProcessInstanceId();
            ProcessInstance parentProcessInstance = dbService.getProcessInstance(parentActivity.getProcessInstanceId());
            UUID parentProcessDefinitionId = parentProcessInstance.getProcessDefinitionId();
            UUID parentToken = parentActivity.getToken();
            BpmnProcessDefinitionModel parentBpmn = bpmnService.getProcessDefinitionModelById(parentProcessDefinitionId);
            BpmnElementModel parentBpmnElement = parentBpmn.getElement(parentActivity.getBpmnElementId());
            if (parentBpmnElement == null) {
                throw new IllegalStateException("Call activity element '" + parentActivity.getBpmnElementId() + "' not found in the parent process definition");
            }

            // Camunda 8: propagateAllChildVariables (default true) copies the child's variables up to the
            // parent; when explicitly false, the child's variables are not propagated.
            // WO-ENG-11: explicit Output mappings take precedence over the toggle — when present they are
            // applied regardless of propagateAllChildVariables, propagating ONLY the mapped variables.
            boolean propagate = Optional.ofNullable(parentBpmnElement)
                .map(BpmnElementModel::getExtensions)
                .map(BpmnElementExtensionModel::getCallActivityExtension)
                .map(ext -> ext.getPropagateAllChildVariables())
                .orElse(Boolean.TRUE);
            List<IoMappingExtensionModel.Mapping> outputMappings = Optional.ofNullable(parentBpmnElement.getExtensions())
                .map(BpmnElementExtensionModel::getIoMappingExtension)
                .map(IoMappingExtensionModel::getOutputs)
                .orElse(null);
            if (outputMappings != null && !outputMappings.isEmpty()) {
                List<ProcessVariable> childVariables = dbService.getVariables(processInstanceId);
                List<ProcessVariable> picked = new ArrayList<>();
                for (IoMappingExtensionModel.Mapping mapping : outputMappings) {
                    ProcessVariable result = elementSupport.evaluateMapping(mapping, childVariables);
                    if (result != null) {
                        picked.add(result);
                    }
                }
                dbService.setVariables(parentProcessInstanceId, picked);
                log.info("{}/{}: Applied {} Output mapping(s) from call activity {} to parent", parentProcessInstanceId, parentToken, picked.size(), parentBpmnElement.getId());
            } else if (propagate) {
                dbService.setVariables(parentProcessInstanceId, dbService.getVariables(processInstanceId));
            }

            log.info("{}/{}: Completing {}: {}/{}", parentProcessInstanceId, parentToken, parentActivity.getType(), parentActivityId, parentActivity.getBpmnElementId());

            proceedToOutgoing(parentProcessInstanceId, parentToken, parentBpmn, parentBpmnElement, executor);
        }
    }
}
