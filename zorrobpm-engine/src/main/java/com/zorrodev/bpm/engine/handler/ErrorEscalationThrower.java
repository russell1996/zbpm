package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.BoundaryEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.EventDefinitionExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.SubProcessExtensionModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles propagation of BPMN errors and escalations through the scope hierarchy.
 * Extracted from ActivityServiceImpl (WO-AUD-23).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ErrorEscalationThrower {

    private final DBService dbService;
    private final BpmnService bpmnService;
    private final FlowNavigator flowNavigator;
    private final EventTrigger eventTrigger;

    /**
     * Propagates a BPMN error from {@code tokenId} outward through the scope hierarchy, looking for
     * an interrupting error boundary that matches {@code errorCode} (a boundary without a code is a
     * catch-all). Search order: enclosing embedded subprocess scopes (innermost first), then — if the
     * instance is a called process — the call activity in the parent instance, recursively. When a
     * handler is found the interrupted scope is cancelled and flow continues from the boundary.
     *
     * @return {@code true} if an error boundary handled the error, {@code false} if it escaped unhandled.
     */
    public boolean throwError(UUID processInstanceId, UUID tokenId, String errorCode, TokenExecutor executor) {
        ProcessInstance pi = dbService.getProcessInstance(processInstanceId);
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(pi.getProcessDefinitionId());

        // walk enclosing embedded-subprocess scopes, innermost first
        Token tok = dbService.getToken(tokenId);
        while (tok != null && tok.getScopeActivityId() != null) {
            Activity scope = dbService.getActivity(tok.getScopeActivityId());
            BpmnElementModel boundary = findErrorBoundary(bpmn, scope.getBpmnElementId(), errorCode);
            if (boundary != null) {
                dbService.cancelActiveActivitiesForToken(tok.getId());
                dbService.cancelActivity(scope.getId());
                log.info("{}: error '{}' caught by boundary {} on subprocess {}", processInstanceId, errorCode, boundary.getId(), scope.getBpmnElementId());
                flowNavigator.proceedToOutgoing(processInstanceId, tok.getParentId(), bpmn, boundary, executor);
                return true;
            }
            tok = tok.getParentId() != null ? dbService.getToken(tok.getParentId()) : null;
        }

        // a top-level error-triggered event sub-process handles the error here (interrupting the main flow)
        BpmnElementModel errorHandler = findEventSubprocessErrorHandler(bpmn, errorCode);
        if (errorHandler != null) {
            log.info("{}: error '{}' caught by event sub-process {}", processInstanceId, errorCode, errorHandler.getId());
            eventTrigger.triggerEventSubprocess(processInstanceId, errorHandler.getId(), List.of(), executor);
            return true;
        }

        // reached the top of this instance: propagate to the parent instance via the call activity
        if (pi.getParentActivityId() != null) {
            Activity callActivity = dbService.getActivity(pi.getParentActivityId());
            UUID parentInstanceId = callActivity.getProcessInstanceId();
            dbService.lockProcessInstance(parentInstanceId);
            ProcessInstance parentPi = dbService.getProcessInstance(parentInstanceId);
            BpmnProcessDefinitionModel parentBpmn = bpmnService.getProcessDefinitionModelById(parentPi.getProcessDefinitionId());
            BpmnElementModel boundary = findErrorBoundary(parentBpmn, callActivity.getBpmnElementId(), errorCode);

            // the error escapes this child instance regardless of whether the parent catches it
            dbService.cancelActiveActivities(processInstanceId);
            dbService.completeProcessInstance(processInstanceId);

            if (boundary != null) {
                dbService.cancelActivity(callActivity.getId());
                log.info("{}: error '{}' caught by boundary {} on call activity {}", parentInstanceId, errorCode, boundary.getId(), callActivity.getBpmnElementId());
                flowNavigator.proceedToOutgoing(parentInstanceId, callActivity.getToken(), parentBpmn, boundary, executor);
                return true;
            }
            // not caught on the call activity: keep propagating within the parent instance
            return throwError(parentInstanceId, callActivity.getToken(), errorCode, executor);
        }

        return false;
    }

    /**
     * Finds an interrupting error boundary attached to {@code attachedToRef} whose error code matches
     * {@code errorCode}; a boundary with no code is a catch-all. Returns {@code null} if none.
     * WO-C8-4: a specific code always wins over catch-all, regardless of XML order.
     */
    private BpmnElementModel findErrorBoundary(BpmnProcessDefinitionModel bpmn, String attachedToRef, String errorCode) {
        BpmnElementModel catchAll = null;
        for (BpmnElementModel element : bpmn.getElements()) {
            if (element.getType() != BpmnElementType.ERROR_BOUNDARY_EVENT) {
                continue;
            }
            String attached = Optional.ofNullable(element.getExtensions())
                .map(BpmnElementExtensionModel::getBoundaryEventExtension)
                .map(BoundaryEventExtensionModel::getAttachedToRef)
                .orElse(null);
            if (!attachedToRef.equals(attached)) {
                continue;
            }
            String boundaryCode = Optional.ofNullable(element.getExtensions())
                .map(BpmnElementExtensionModel::getEventDefinition)
                .map(EventDefinitionExtensionModel::getCode)
                .orElse(null);
            if (boundaryCode != null && boundaryCode.equals(errorCode)) {
                return element; // WO-C8-4: specific code wins regardless of iteration order
            }
            if (boundaryCode == null && catchAll == null) {
                catchAll = element; // remember the first catch-all as fallback
            }
        }
        return catchAll;
    }

    /**
     * Finds a top-level error-triggered event sub-process matching {@code errorCode}
     * (null code = catch-all). WO-C8-4: a specific code always wins over catch-all.
     */
    private BpmnElementModel findEventSubprocessErrorHandler(BpmnProcessDefinitionModel bpmn, String errorCode) {
        BpmnElementModel catchAll = null;
        for (BpmnElementModel element : bpmn.getEventSubProcesses()) {
            SubProcessExtensionModel ext = Optional.ofNullable(element.getExtensions())
                .map(BpmnElementExtensionModel::getSubProcessExtension)
                .orElse(null);
            if (ext == null || !ext.isErrorTriggered()) {
                continue;
            }
            if (ext.getTriggerErrorCode() != null && ext.getTriggerErrorCode().equals(errorCode)) {
                return element; // WO-C8-4: specific code wins regardless of iteration order
            }
            if (ext.getTriggerErrorCode() == null && catchAll == null) {
                catchAll = element; // remember the first catch-all as fallback
            }
        }
        return catchAll;
    }

    public String escalationCode(BpmnElementModel element) {
        return Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getEventDefinition)
            .map(EventDefinitionExtensionModel::getCode)
            .orElse(null);
    }

    /**
     * Propagates an escalation from {@code tokenId} outward through the scope hierarchy, looking for an
     * escalation boundary that matches {@code escalationCode} (a boundary without a code is a catch-all).
     * Mirrors {@link #throwError} but supports non-interrupting boundaries (the host scope keeps running
     * and a parallel branch is spawned) and never cancels the instance or raises an incident, because an
     * escalation is non-critical.
     *
     * @return {@code true} if an interrupting boundary on a scope enclosing {@code tokenId} fired
     *         (so the throwing path was cancelled and must not continue), {@code false} otherwise.
     */
    public boolean throwEscalation(UUID processInstanceId, UUID tokenId, String escalationCode, TokenExecutor executor) {
        ProcessInstance pi = dbService.getProcessInstance(processInstanceId);
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(pi.getProcessDefinitionId());

        // walk enclosing embedded-subprocess scopes, innermost first
        Token tok = dbService.getToken(tokenId);
        while (tok != null && tok.getScopeActivityId() != null) {
            Activity scope = dbService.getActivity(tok.getScopeActivityId());
            BpmnElementModel boundary = findEscalationBoundary(bpmn, scope.getBpmnElementId(), escalationCode);
            if (boundary != null) {
                if (isInterrupting(boundary)) {
                    dbService.cancelActiveActivitiesForToken(tok.getId());
                    dbService.cancelActivity(scope.getId());
                    log.info("{}: escalation '{}' caught (interrupting) by boundary {} on subprocess {}", processInstanceId, escalationCode, boundary.getId(), scope.getBpmnElementId());
                    flowNavigator.proceedToOutgoing(processInstanceId, tok.getParentId(), bpmn, boundary, executor);
                    return true;
                }
                Token branch = dbService.createToken(tok.getParentId());
                log.info("{}: escalation '{}' caught (non-interrupting) by boundary {} on subprocess {} (branch token {})", processInstanceId, escalationCode, boundary.getId(), scope.getBpmnElementId(), branch.getId());
                flowNavigator.proceedToOutgoing(processInstanceId, branch.getId(), bpmn, boundary, executor);
                return false;
            }
            tok = tok.getParentId() != null ? dbService.getToken(tok.getParentId()) : null;
        }

        // reached the top of this instance: propagate to the parent instance via the call activity
        if (pi.getParentActivityId() != null) {
            Activity callActivity = dbService.getActivity(pi.getParentActivityId());
            UUID parentInstanceId = callActivity.getProcessInstanceId();
            dbService.lockProcessInstance(parentInstanceId);
            ProcessInstance parentPi = dbService.getProcessInstance(parentInstanceId);
            BpmnProcessDefinitionModel parentBpmn = bpmnService.getProcessDefinitionModelById(parentPi.getProcessDefinitionId());
            BpmnElementModel boundary = findEscalationBoundary(parentBpmn, callActivity.getBpmnElementId(), escalationCode);

            if (boundary != null) {
                if (isInterrupting(boundary)) {
                    // interrupting escalation on a call activity cancels the child instance
                    dbService.cancelActiveActivities(processInstanceId);
                    dbService.completeProcessInstance(processInstanceId);
                    dbService.cancelActivity(callActivity.getId());
                    log.info("{}: escalation '{}' caught (interrupting) by boundary {} on call activity {}", parentInstanceId, escalationCode, boundary.getId(), callActivity.getBpmnElementId());
                    flowNavigator.proceedToOutgoing(parentInstanceId, callActivity.getToken(), parentBpmn, boundary, executor);
                    return true; // child instance was cancelled, so the throwing path is gone
                }
                // the child instance keeps running; a parallel branch is spawned in the parent
                Token branch = dbService.createToken(callActivity.getToken());
                log.info("{}: escalation '{}' caught (non-interrupting) by boundary {} on call activity {} (branch token {})", parentInstanceId, escalationCode, boundary.getId(), callActivity.getBpmnElementId(), branch.getId());
                flowNavigator.proceedToOutgoing(parentInstanceId, branch.getId(), parentBpmn, boundary, executor);
                return false;
            }
            // not caught on the call activity: keep propagating within the parent instance for handling,
            // but the child's throwing path is not interrupted by it
            throwEscalation(parentInstanceId, callActivity.getToken(), escalationCode, executor);
            return false;
        }

        log.info("{}: escalation '{}' escaped unhandled (non-critical, ignored)", processInstanceId, escalationCode);
        return false;
    }

    private boolean isInterrupting(BpmnElementModel boundary) {
        return Optional.ofNullable(boundary.getExtensions())
            .map(BpmnElementExtensionModel::getBoundaryEventExtension)
            .map(BoundaryEventExtensionModel::isInterrupting)
            .orElse(true);
    }

    /**
     * Finds an escalation boundary attached to {@code attachedToRef} whose escalation code matches
     * {@code escalationCode}; a boundary with no code is a catch-all. Returns {@code null} if none.
     * WO-C8-4: a specific code always wins over catch-all, regardless of XML order.
     */
    private BpmnElementModel findEscalationBoundary(BpmnProcessDefinitionModel bpmn, String attachedToRef, String escalationCode) {
        BpmnElementModel catchAll = null;
        for (BpmnElementModel element : bpmn.getElements()) {
            if (element.getType() != BpmnElementType.ESCALATION_BOUNDARY_EVENT) {
                continue;
            }
            String attached = Optional.ofNullable(element.getExtensions())
                .map(BpmnElementExtensionModel::getBoundaryEventExtension)
                .map(BoundaryEventExtensionModel::getAttachedToRef)
                .orElse(null);
            if (!attachedToRef.equals(attached)) {
                continue;
            }
            String boundaryCode = Optional.ofNullable(element.getExtensions())
                .map(BpmnElementExtensionModel::getEventDefinition)
                .map(EventDefinitionExtensionModel::getCode)
                .orElse(null);
            if (boundaryCode != null && boundaryCode.equals(escalationCode)) {
                return element; // WO-C8-4: specific code wins regardless of iteration order
            }
            if (boundaryCode == null && catchAll == null) {
                catchAll = element; // remember the first catch-all as fallback
            }
        }
        return catchAll;
    }
}
