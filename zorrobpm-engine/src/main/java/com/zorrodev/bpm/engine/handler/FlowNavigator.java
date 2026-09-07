package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.bpmn.model.*;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ScriptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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

    /**
     * Follows every outgoing sequence flow of {@code element} unconditionally and executes the
     * target of each. Shared "continue from here" step used by start events, completed tasks,
     * signalled wait states and parent continuation after a subprocess/call activity ends.
     */
    public void proceedToOutgoing(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel element, TokenExecutor executor) {
        // WO-C8-32: ad-hoc join arrival pre-check (NOT only the dead-end branch below).
        // Chained middles (a→b: a HAS outgoing) never reach the dead-end return, yet an
        // activated middle's completion must still count — sequence flows inside ad-hoc are
        // docs-allowed, and a dead-end-only hook would hang such scopes forever (arrival 1
        // of 2). Dead ends flow through here too, so this one call site covers both.
        // Returns true only when a scope finished and consumed the branch.
        if (handleAdHocArrival(processInstanceId, tokenId, bpmn, element, executor)) {
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
     * Per arrival, in order: output aggregation (mirror of MI, per completed inner flow),
     * arrival record on the scope's generic-counter key, completion test ({@code arrived >=
     * expected} when no condition, else the FEEL {@code completionCondition} evaluated
     * against the live root variables — the completing tail already merged its outputs
     * there). Unfinished scopes leave the token alone; a finished scope completes its
     * container activity, cancels the token-mates (unless {@code cancelRemainingInstances}
     * is false) and continues past the ad-hoc element.
     *
     * @return true when a scope finished here and consumed this branch (caller must return
     *         WITHOUT flowing the completing element's own outgoing — the scope took over).
     */
    public boolean handleAdHocArrival(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn,
            BpmnElementModel element, TokenExecutor executor) {
        // One indexed read of the instance's active activities per proceedToOutgoing call
        // (created/in-progress only — usually a handful of rows). No new DB surface: the
        // repository method already exists (cancel paths use it).
        List<Activity> scopes = dbService.getActiveActivities(processInstanceId).stream()
            .filter(a -> tokenId.equals(a.getToken()) && a.getType() == BpmnElementType.AD_HOC_SUB_PROCESS)
            .toList();
        if (scopes.isEmpty()) {
            return false;
        }
        boolean consumed = false;
        for (Activity scope : scopes) {
            AdHocJoin.ScopeState state = AdHocJoin.resolve(dbService, objectMapper, processInstanceId, scope.getId());
            if (state == null || !state.activatedIds().contains(element.getId())) {
                continue;
            }
            aggregateAdHocOutput(processInstanceId, bpmn, scope);
            String key = AdHocJoin.joinKey(scope.getId(), state.batchUuid());
            dbService.recordParallelGatewayArrival(processInstanceId, key, AdHocJoin.arrivalMarker(element.getId()));
            Integer expected = dbService.getInclusiveExpected(processInstanceId, key);
            int arrived = dbService.getParallelGatewayArrivedFlows(processInstanceId, key).size();
            boolean done = (expected != null && arrived >= expected)
                || adHocConditionMet(processInstanceId, bpmn, scope);
            if (!done) {
                log.info("{}/{}: Ad-hoc subprocess {} not ready: {} of {} inner flows done",
                    processInstanceId, tokenId, scope.getBpmnElementId(), arrived, expected);
                continue;
            }
            finishAdHocScope(processInstanceId, tokenId, bpmn, scope, key, executor);
            consumed = true;
        }
        return consumed;
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

    /** WO-C8-32: scope-done tail — clear bookkeeping, complete the container, cancel the rest, continue. */
    private void finishAdHocScope(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn,
            Activity scope, String key, TokenExecutor executor) {
        dbService.clearParallelGatewayArrivals(processInstanceId, key);
        dbService.completeActivity(scope.getId());
        BpmnElementModel scopeElement = bpmn.getElement(scope.getBpmnElementId());
        boolean cancelRest = Optional.ofNullable(scopeElement)
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
            List<Activity> mates = dbService.getActiveActivities(processInstanceId).stream()
                .filter(a -> tokenId.equals(a.getToken()) && a.getType() != BpmnElementType.AD_HOC_SUB_PROCESS)
                .toList();
            for (Activity mate : mates) {
                dbService.cancelActivity(mate.getId());
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
                List<ProcessVariable> variables = dbService.getVariables(processInstanceId);
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
        Token endToken = dbService.getToken(tokenId);
        if (endToken != null && endToken.getScopeActivityId() != null) {
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
            Token currentToken = dbService.getToken(currentTokenId);
            if (currentToken != null && currentToken.getParentId() != null) {
                Token parentToken = dbService.getToken(currentToken.getParentId());
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
