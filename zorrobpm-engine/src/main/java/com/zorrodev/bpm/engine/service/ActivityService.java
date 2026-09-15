package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;

import java.util.List;
import java.util.UUID;

public interface ActivityService {

    void execute(UUID processInstanceId, UUID tokenId, String bpmnElementId);

    void completeServiceTask(UUID activityId, List<ProcessVariable> variables);

    /**
     * WO-C8-33: completes a job-worker ad-hoc scope job with its structured result
     * (the typed counterpart of {@link #completeServiceTask} — flat variables cannot
     * carry activateElements[] + flags).
     */
    void completeAdHocScopeJob(UUID scopeActivityId, com.zorrodev.bpm.contract.dto.AdHocJobResultDTO result);

    /**
     * Reports a service-task (job) failure from a worker. {@code retries} follows Camunda {@code failJob}:
     * when non-null the retry budget is set to it ({@code 0} raises the incident immediately); when null the
     * budget is decremented by one. While retries remain the job is re-dispatched; when exhausted the activity
     * is marked ERROR and an incident carrying {@code errorMessage} is raised. The token stays parked.
     */
    void failServiceTask(UUID serviceTaskId, String errorMessage, Integer retries);

    void completeUserTask(UUID activityId, List<ProcessVariable> variables);

    /**
     * WO-C8-28: phase-aware assignment (assign-API) — parks in an assigning phase
     * when the element declares assigning listeners, applies immediately otherwise.
     */
    void assignUserTask(UUID taskId, String assignee);

    /**
     * WO-C8-28: phase-aware claim (Tasklist assignment) — same assigning-phase
     * discipline as {@link #assignUserTask}.
     */
    void claimUserTask(UUID taskId, String assignee);

    /**
     * Resumes a token parked at a wait state (intermediate/message/timer catch event):
     * applies the given variables, completes the waiting activity and follows its outgoing flows.
     * Called by the timer scheduler and message-correlation subsystems.
     */
    void signal(UUID activityId, List<ProcessVariable> variables);

    /**
     * Correlates a message to tokens waiting at message catch events: finds active subscriptions
     * for {@code messageName} (optionally scoped to {@code processInstanceId}), applies the given
     * variables and resumes each waiting token.
     */
    void correlateMessage(String messageName, UUID processInstanceId, List<ProcessVariable> variables);

    /**
     * Correlates a message by a correlation-key value: only subscriptions whose stored key matches are
     * resumed (targeted delivery among instances sharing a message name). A null {@code correlationKey}
     * falls back to name-based correlation (optionally scoped to {@code processInstanceId}).
     */
    void correlateMessage(String messageName, String correlationKey, UUID processInstanceId, List<ProcessVariable> variables);

    /**
     * Fires an interrupting timer boundary: if the host activity is still active it is cancelled
     * and flow continues from the boundary event's outgoing flows. Called by the timer scheduler.
     */
    void fireBoundaryTimer(UUID hostActivityId, String boundaryElementId);

    /** Fires a timer-started event sub-process (the due timer registered at instance start). */
    void fireEventSubprocessTimer(UUID processInstanceId, String eventSubprocessId);

    void resolveIncident(UUID incidentId, List<ProcessVariable> variables);

    UUID startProcessInstance(UUID parentProcessInstanceId, UUID processDefinitionId, List<ProcessVariable> variables);

    /**
     * WO-API-1 (API-7): тот же старт, но initiator пишется в том же INSERT
     * create (атомарно). Null = без инициатора (старый путь).
     */
    default UUID startProcessInstance(UUID parentProcessInstanceId, UUID processDefinitionId,
            List<ProcessVariable> variables, String claimedInitiator) {
        return startProcessInstance(parentProcessInstanceId, processDefinitionId, variables);
    }

    /**
     * Starts a new instance beginning at a specific start element (used by message/timer start
     * triggers, which begin at their own start node).
     */
    UUID startProcessInstanceFromStartEvent(UUID processDefinitionId, String startElementId, List<ProcessVariable> variables);

    /**
     * Enters a service task: creates the activity, enqueues the job, and applies IO mappings.
     */
    void enterServiceTask(UUID processInstanceId, UUID token, BpmnElementModel bpmnElement);

    /**
     * Broadcasts a signal to every active subscriber (1:N) and signal-started subscriptions.
     */
    void broadcastSignal(String signalName, List<ProcessVariable> variables);

    /**
     * Executes a BPMN element (dispatches to the appropriate handler).
     */
    void execute(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel element);

    /**
     * Evaluates a message subscriber's correlation-key FEEL expression.
     */
    String evaluateCorrelationKey(BpmnElementModel element, UUID processInstanceId);

    /**
     * Evaluates conditional event expressions and fires tokens for any satisfied conditions.
     */
    void triggerConditionalEvents(UUID processInstanceId);

    /** Completes the current branch: finishes embedded subprocess scope or completes the process instance. */
    void finishBranch(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn);

    /**
     * Propagates an error through enclosing scopes looking for a matching error boundary.
     * @return true if an error boundary handled the error, false if unhandled.
     */
    boolean throwError(UUID processInstanceId, UUID tokenId, String errorCode);

    /** Extracts the escalation code from an element's event definition extensions. */
    String escalationCode(BpmnElementModel element);

    /**
     * Propagates an escalation through enclosing scopes looking for a matching escalation boundary.
     * @return true if an interrupting boundary fired, false otherwise.
     */
    boolean throwEscalation(UUID processInstanceId, UUID tokenId, String escalationCode);
}
