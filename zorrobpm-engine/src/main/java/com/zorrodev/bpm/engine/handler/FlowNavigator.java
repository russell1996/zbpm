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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
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
    // WO-C8-40: MI-хвост выхода инстанса подпроцесса (агрегация + join).
    // Лениво через provider: MultiInstanceExecutor тянет SubProcessHandler
    // (тот зависит от MultiInstanceExecutor) — прямая пара дала бы Spring-цикл.
    private final org.springframework.beans.factory.ObjectProvider<MultiInstanceExecutor> multiInstanceExecutorProvider;

    /**
     * Follows every outgoing sequence flow of {@code element} unconditionally and executes the
     * target of each. Shared "continue from here" step used by start events, completed tasks,
     * signalled wait states and parent continuation after a subprocess/call activity ends.
     *
     * WO-QW-1 Q-7: history kept to 2 lines + WO ref — the full ad-hoc HOLD story lives in
     * docs/internal
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
                // WO-AUDIT-11 (F-09): тот же stop-условие, что и в ParallelGatewayHandler —
                // погашенный скоуп не активирует оставшиеся ветви неявного форка.
                if (!elementSupport.isScopeAlive(processInstanceId, tokenId)) {
                    log.info("{}/{}: scope погашен во время неявного разветвления {} — оставшиеся ветви не активируются",
                        processInstanceId, tokenId, element.getId());
                    break;
                }
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
     * HOLD-fix (2026-09-07, живой дефект maintainer): прибытие корня пишется в момент его
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
     *         otherwise. Full HOLD story: docs/internal
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
        // WO-C8-37 (C37-1): scopes are found by scope-token identity, not by sharing
        // the arrival token. A live scope row owns exactly one scope token
        // (dbService.createToken(incoming, scopeActivityId) at entry — its
        // scopeActivityId points back at the scope row). The arrival belongs to
        // the scope when walking up from the arrival token meets that scope token
        // (non-interrupting boundary branches fork child tokens inside the scope,
        // the walk covers them). The walk uses only the existing findToken reads —
        // no new repository surface. Foreign scopes on the same incoming token are
        // never touched.
        Set<UUID> scopeIdsOnArrival = new HashSet<>();
        UUID cursor = tokenId;
        while (cursor != null) {
            com.zorrodev.bpm.engine.dto.Token cursorToken = dbService.findToken(cursor).orElse(null);
            if (cursorToken == null) {
                break;
            }
            if (cursorToken.getScopeActivityId() != null) {
                scopeIdsOnArrival.add(cursorToken.getScopeActivityId());
            }
            cursor = cursorToken.getParentId();
        }
        List<Activity> scopes = active.stream()
            .filter(a -> a.getType() == BpmnElementType.AD_HOC_SUB_PROCESS && scopeIdsOnArrival.contains(a.getId()))
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
                        finishAdHocScope0(processInstanceId, resumeTokenForScope(scope, tokenId), bpmn, scope,
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
                    finishAdHocScope0(processInstanceId, resumeTokenForScope(scope, tokenId), bpmn, scope, key,
                        executor, null, active, cancelled);
                    outcome = ArrivalOutcome.SCOPE_FINISHED;
                } else {
                    log.info("{}/{}: Ad-hoc subprocess {} root {} done, chain continues",
                        processInstanceId, tokenId, scope.getBpmnElementId(), element.getId());
                }
                continue;
            }
            if (evaluateScopeDone(processInstanceId, bpmn, scope, state, active, cancelled)) {
                finishAdHocScope0(processInstanceId, resumeTokenForScope(scope, tokenId), bpmn, scope, key,
                    executor, null, active, cancelled);
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
     * WO-C8-37 (C37-1): the token a finished ad-hoc scope continues on — the scope
     * row's INCOMING token. Entry keeps the scope row on the incoming token and
     * creates the scope token as its CHILD (createToken(incoming, scopeActivity)).
     * {@code scope.getToken()} may be either shape (the row's own token, or the
     * scope token when a fixture moved it): walk up until the token whose
     * scopeActivityId is this scope — its PARENT is the incoming branch. Falls
     * back to the arrival token when the chain is unreadable (defensive only).
     */
    private UUID resumeTokenForScope(Activity scope, UUID arrivalTokenId) {
        UUID cursor = scope.getToken();
        while (cursor != null) {
            com.zorrodev.bpm.engine.dto.Token cursorToken =
                dbService.findToken(cursor).orElse(null);
            if (cursorToken == null) {
                break;
            }
            if (scope.getId() != null && scope.getId().equals(cursorToken.getScopeActivityId())) {
                return cursorToken.getParentId() == null ? arrivalTokenId : cursorToken.getParentId();
            }
            cursor = cursorToken.getParentId();
        }
        return scope.getToken() == null ? arrivalTokenId : scope.getToken();
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
        // Quiescence: nothing unfinished left INSIDE the scope subtree besides
        // ad-hoc containers themselves (the completing element itself is already
        // COMPLETED — its tail ran before this hook). Evaluated ONLY here, never
        // on a middle: a middle's own downstream is not created yet at hook time,
        // so "quiet" would lie for it.
        // WO-C8-37 (раунд 2, БЛОКИРУЮЩАЯ №1): scope membership is read off the
        // token parent chain (ElementSupport.enclosingScopeChain — the same walk
        // the finish tail uses for mates), NOT off token equality with
        // scope.getToken(). scope.getToken() is the INCOMING token; since C37-1
        // inner elements live on the scope token (its child), token equality
        // went blind: the scope finished early and cancelled live inner tasks.
        // The chain walk additionally covers non-interrupting-boundary child
        // tokens and nested ad-hoc subtrees (their rows stay AD_HOC-excluded,
        // their live inner activities still block) — a plain scope-token
        // equality would still miss both. Same construction as the mates filter
        // below, so quiescence and cancel-confinement can never disagree on
        // what "inside the scope" means.
        List<Activity> live = active.stream()
            .filter(a -> !cancelled.contains(a.getId()))
            .toList();
        return elementSupport.filterActivitiesInScope(processInstanceId, live, scope.getId()).stream()
            .noneMatch(a -> a.getType() != BpmnElementType.AD_HOC_SUB_PROCESS);
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
        // WO-C8-37 (C37-1): same incoming-token resume as the arrival path — see
        // resumeTokenForScope. The worker path has no arrival token at hand, only
        // the scope row, so it resolves the incoming token itself.
        finishAdHocScope0(processInstanceId, resumeTokenForScope(scope, tokenId), bpmn, scope, key, executor,
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
            // WO-C8-37 (C37-1): mates are the activities on the SCOPE token
            // (scope.getToken()) — inner elements live there now, not on the arrival
            // token. A nested ad-hoc finishing inside an outer scope cancels only its
            // own subtree; the outer scope's own inner elements on the outer scope
            // token are untouched.
            List<Activity> active = activeSnapshot != null
                ? activeSnapshot : dbService.getActiveActivities(processInstanceId);
            // WO-C8-37 (C37-1): mates are the activities INSIDE the scope —
            // enclosingScopeChain contains the scope (scope token or its
            // descendants), wherever the arrival token landed. The old
            // token-equality check died with the scope token: inner elements live
            // on the scope token while scope.getToken() is the incoming token.
            // Fresh read every time (NOT the traversal snapshot): the snapshot
            // predates worker-driven activations/completions that run between the
            // arrival snapshot and this tail (job-worker finish path).
            List<Activity> mates = elementSupport.filterActivitiesInScope(
                    processInstanceId, dbService.getActiveActivities(processInstanceId), scope.getId()).stream()
                .filter(a -> !cancelled.contains(a.getId())
                    && a.getType() != BpmnElementType.AD_HOC_SUB_PROCESS)
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
            if (expression == null) {
                // WO-DIFF-10: поток без условия и не default просто не матчится
                // (тот же исход, что «условие ложно») — evaluateScript(null) крашился
                // сырой NPE из ScriptServiceImpl.codeRef, из-за чего становился
                // недостижим чистый IllegalStateException в ExclusiveGatewayHandler.
                // default-поток с пустым условием вёл себя так же (null = не взят
                // здесь); сам default идёт отдельным вызовом с processExpression=false.
                return null;
            }
            {
                // WO-C8-45: без кэша — тот же chain-контекст по токену, что кэш
                // gateway-хендлеров (activity-цепочка gateway и токен-цепочка
                // совпадают: gateway создан на этом токене). Внешние вызывающие
                // без кэша (белый ящик, операторские пути) видят скоуп тоже.
                List<ProcessVariable> variables = cachedVariables != null ? cachedVariables
                    : elementSupport.chainVariablesForTokenNames(processInstanceId, tokenId,
                        FeelVariableNames.extract(expression));
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
     * WO-C8-40: MI-хвост завершения инстанса подпроцесса — зеркало task-хвостов
     * в {@code CompletionService} (агрегация → дроп локалов → join). Вызывается
     * из {@link #finishBranch} на scope-ветке, когда контейнер — MI-подпроцесс.
     *
     * @return true, если инстансов ещё ждут (хвост завершён, продолжать нечего);
     *         false, если join собран и вызывающий продолжает историческим путём
     *         (выходы контейнера → complete → исходящий поток один раз).
     *
     * <p>WO-C8-43: контейнер инстанса completes ЗДЕСЬ, до join-решения — каждая
     * ветка закрывает свой контейнер в момент своего внутреннего end (строка
     * завершена, расписание границ инстанса мертво: fireBoundary на такой
     * строке — no-op). Join/исходящий поток — только на последнем прибывшем
     * (возврат multiInstanceContinue), sequential-спaун — тоже там. Раньше
     * контейнер completes только у последней ветки: остальные оставались
     * CREATED навсегда (orphan в диагностике/UI, врёт activeTokenCount).
     * Sequential: спаун следующего идёт на входящем токене контейнера
     * (miToken), строка этого контейнера уже COMPLETED — порядок безразличен:
     * спаун не читает статус текущего контейнера.
     */
    private boolean finishMiSubProcessInstance(UUID processInstanceId, Token endToken,
            BpmnProcessDefinitionModel bpmn, BpmnElementModel subProcessElement,
            UUID subProcessActivityId, TokenExecutor executor) {
        MultiInstanceExecutor mi = multiInstanceExecutorProvider.getIfAvailable();
        if (mi == null) {
            throw new com.zorrodev.bpm.contract.exception.EngineException("Multi-instance "
                + subProcessElement.getId() + ": MultiInstanceExecutor bean unavailable for instance completion");
        }
        // WO-REL-59/63: join сериализуется с отменой инстанса — тот же порядок
        // instance-lock ПЕРВЫМ, что task-хвосты (lockInstanceFirst).
        Activity scope = elementSupport.lockInstanceFirst(subProcessActivityId);
        if (scope == null || (scope.getStatus()
                != com.zorrodev.bpm.engine.entity.ActivityStatus.CREATED
            && scope.getStatus() != com.zorrodev.bpm.engine.entity.ActivityStatus.IN_PROGRESS)) {
            // Гонка с отменой: scope уже снят — тихий no-op, как stale-пути WO-REL-30.
            log.info("{}/{}: MI subprocess instance {} already {} — completion swallowed by cancel",
                processInstanceId, endToken.getId(), subProcessActivityId,
                scope == null ? "gone" : scope.getStatus());
            return true;
        }
        mi.aggregateMultiInstanceOutput(processInstanceId, subProcessActivityId, subProcessElement);
        dbService.deleteVariables(processInstanceId, subProcessActivityId);
        // WO-C8-43: контейнер ЭТОГО инстанса completes сейчас, не дожидаясь
        // остальных (иначе orphan). Ниже multiInstanceContinue решает только
        // судьбу join: не-last → true (хвост завершён, вызывающий выходит),
        // last → false (вызывающий доводит выходы контейнера и исходящий
        // поток один раз; completeActivity на уже-COMPLETED — идемпотентен).
        dbService.completeActivity(subProcessActivityId);
        // Токен MI-инстансов — входящий токен контейнера (спaун шёл на нём):
        // join bookkeeping идёт на нём же.
        UUID miToken = scope.getToken();
        // WO-C8-41 (F4): ранняя добивка сиблингов при completionCondition —
        // ВНУТРИ multiInstanceContinue (там видны arrived/expected и отличие
        // раннего done от полного; безусловная добивка здесь убивала бы
        // соседние outer-инстансы при каждом нормальном join — поймано
        // паритетным S8a: y-инстансы гасли на join'е x).
        return !mi.multiInstanceContinue(processInstanceId, miToken, subProcessElement,
            subProcessActivityId, executor);
    }

    /**
     * WO-C8-45 (б, tail): первый токен вверх от {@code endToken}, не несущий
     * скоуп {@code deadScopeActivityId}. Null-safe: оборванная цепочка даёт
     * null (как прежний прямой {@code getParentId()}).
     */
    private UUID tokenAboveScope(Token endToken, UUID deadScopeActivityId) {
        UUID current = endToken == null ? null : endToken.getParentId();
        while (current != null) {
            Token token = dbService.findToken(current).orElse(null);
            if (token == null || !deadScopeActivityId.equals(token.getScopeActivityId())) {
                break;
            }
            current = token.getParentId();
        }
        return current;
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
            Activity subProcessActivity = dbService.getActivity(subProcessActivityId);
            BpmnElementModel subProcessElement = bpmn.getElement(subProcessActivity.getBpmnElementId());
            // WO-C8-37 (C37-1): ad-hoc containers finished by their own join
            // machinery handle their continuation THERE (finishAdHocScope0, on the
            // incoming token). finishBranch must not repeat it — whichever path
            // finished the ad-hoc scope already continued past the container
            // (live repro: the join finishes the scope on the incoming token,
            // then the end event on the scope token re-enters here and spawns a
            // duplicate post-scope flow on the WRONG token — the instance then
            // never completes because the branch counter bookkeeping diverges).
            // Other scope containers keep the historical embedded-subprocess exit
            // below. AD_HOC scope tokens henceforth die with their scope: the one
            // path that legitimately arrives here while the ad-hoc scope is still
            // live is terminate (EndEventHandler routes it past the join), and it
            // needs the same close-and-continue tail as an embedded subprocess.
            if (subProcessElement != null
                && subProcessElement.getType() == BpmnElementType.AD_HOC_SUB_PROCESS
                && (subProcessActivity.getStatus() == com.zorrodev.bpm.engine.entity.ActivityStatus.COMPLETED
                    || subProcessActivity.getStatus() == com.zorrodev.bpm.engine.entity.ActivityStatus.CANCELLED)) {
                log.info("{}/{}: finishBranch on token of already-{} ad-hoc scope {} — completed via the join path, branch consumed",
                    processInstanceId, tokenId, subProcessActivity.getStatus(), subProcessActivity.getBpmnElementId());
                // Consume the branch WITHOUT touching the branch counter: the scope
                // token never holds a fork counter (C37-1 proof: decrementing it
                // returns -1 and completes the instance early — live repro
                // two-tasks/output tests), and the token row cannot be deleted
                // (fk_activities__token: history rows still reference it). The
                // join's incoming-token continuation already carries the branch;
                // a bare return is the consumption. See the TERMINAL-ROW
                // INVARIANT in DBService (activities keep their token forever) —
                // never deleteToken a token with activity history.
                return;
            }
            // WO-DIFF-1 п.1: promote the container's output mappings to root BEFORE
            // completing it (output sources evaluate against the still-live sub scope
            // via ElementSupport's enclosing-chain reads), then drop the closed scope's
            // locals — the same task-local lifecycle task completion uses. Gated to
            // SUB_PROCESS only: event sub-processes share the parse but stay runtime-inert
            // here (different trigger/lifecycle path), and ad-hoc containers have their
            // own completion machinery below. No ioMapping → zero behaviour change.
            //
            // WO-C8-40: MI-инстанс подпроцесса идёт MI-хвостом (агрегация outputElement
            // в outputCollection по слоту loopCounter + join через multiInstanceContinue
            // под instance-lock; исходящий поток — только после join, sequential спавнит
            // следующий инстанс). Локалы инстанса дропаются, как у task-хвостов.
            // НЕ-MI подпроцесс — историческим путём ниже без изменений (критерий 4).
            if (subProcessElement != null
                && subProcessElement.getType() == BpmnElementType.SUB_PROCESS
                && subProcessElement.getExtensions() != null
                && subProcessElement.getExtensions().getMultiInstanceExtension() != null) {
                if (finishMiSubProcessInstance(processInstanceId, endToken, bpmn,
                    subProcessElement, subProcessActivityId, executor)) {
                    return;
                }
                // multiInstanceContinue сказал «join собран»: хвост ниже доводит
                // контейнер (выходы в root по WO-DIFF-1 — контейнерные маппинги
                // НЕ-MI-семантики; сам контейнер уже COMPLETED в
                // finishMiSubProcessInstance — WO-C8-43, повторный
                // completeActivity ниже идемпотентен) и продолжает исходящий
                // поток один раз.
            }
            if (subProcessElement != null
                && subProcessElement.getType() == BpmnElementType.SUB_PROCESS
                && subProcessElement.getExtensions() != null
                && subProcessElement.getExtensions().getIoMappingExtension() != null) {
                elementSupport.applyIoMappings(processInstanceId, subProcessActivityId, subProcessElement, false);
                dbService.deleteVariables(processInstanceId, subProcessActivityId);
            }
            dbService.completeActivity(subProcessActivityId);
            // WO-C8-45 (б, tail): продолжать МИМО мёртвого скоупа контейнера.
            // End мог прийти на форк-токене inclusive-сплита (scope — строка
            // контейнера): его parent — scope-токен с ТЕМ ЖЕ мёртвым scope.
            // Нисходящий end на таком токене ре-enterит этот же SUB-path,
            // глотается (контейнер уже COMPLETED, «swallowed by cancel») и
            // выходит ДО счётчика ветвей — инстанс молча RUNNING без живых
            // и без инцидентов. Подъём до первого токена вне скоупа чинит:
            // plain-кейс (end прямо на scope-токене → его parent) подъём не
            // меняет, MI-форк/вложенные форки — выводит на живой токен.
            UUID parentTokenId = tokenAboveScope(endToken, subProcessActivityId);
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
            UUID parentProcessInstanceId = parentActivity.getProcessInstanceId();
            dbService.lockProcessInstance(parentProcessInstanceId);
            // WO-AUDIT-12 (F-02): родитель мог быть отменён или завершён, пока
            // ребёнок жил (операторская отмена родителя не каскадировала на
            // дочерние инстансы call activity). Продолжение отменённого потока
            // ЗАПРЕЩЕНО: оно переписывало историю (CANCELLED→COMPLETED) и
            // создавало живые activity-строки в уже завершённом инстансе.
            // Проверка — ПОД instance-lock'ом родителя (его берёт и путь отмены:
            // ProcessInstanceRuntimeOperationsImpl#cancelProcessInstance), то
            // есть решение принимается атомарно с отменой.
            ProcessInstance parentProcessInstance = dbService.getProcessInstance(parentProcessInstanceId);
            if (parentProcessInstance.isCancelled() || parentProcessInstance.getCompletedAt() != null) {
                log.info("{}/{}: parent instance {} is {} — the child's continuation is dropped "
                    + "(WO-AUDIT-12 F-02)", processInstanceId, parentActivityId, parentProcessInstanceId,
                    parentProcessInstance.isCancelled() ? "CANCELLED" : "COMPLETED");
                return;
            }
            dbService.completeActivity(parentActivityId);

            UUID parentToken = parentActivity.getToken();
            BpmnProcessDefinitionModel parentBpmn = bpmnService.getProcessDefinitionModelById(parentProcessInstance.getProcessDefinitionId());
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
                // WO-C8-58: target с точкой → вложенный контекст (Zeebe 8.5).
                IoMappingTargetWriter writer = new IoMappingTargetWriter(elementSupport.objectMapper());
                Map<String, ProcessVariable> picked = new LinkedHashMap<>();
                for (IoMappingExtensionModel.Mapping mapping : outputMappings) {
                    ProcessVariable result = elementSupport.evaluateMapping(mapping, childVariables);
                    if (result != null) {
                        ProcessVariable stored = writer.persist(result);
                        picked.put(stored.getName(), stored);
                    }
                }
                dbService.setVariables(parentProcessInstanceId, new ArrayList<>(picked.values()));
                log.info("{}/{}: Applied {} Output mapping(s) from call activity {} to parent", parentProcessInstanceId, parentToken, picked.size(), parentBpmnElement.getId());
            } else if (propagate) {
                dbService.setVariables(parentProcessInstanceId, dbService.getVariables(processInstanceId));
            }

            log.info("{}/{}: Completing {}: {}/{}", parentProcessInstanceId, parentToken, parentActivity.getType(), parentActivityId, parentActivity.getBpmnElementId());

            proceedToOutgoing(parentProcessInstanceId, parentToken, parentBpmn, parentBpmnElement, executor);
        }
    }
}
