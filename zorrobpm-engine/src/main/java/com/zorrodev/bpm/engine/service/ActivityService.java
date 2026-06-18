package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.model.ProcessVariable;

import java.util.List;
import java.util.UUID;

public interface ActivityService {

    void execute(UUID processInstanceId, UUID tokenId, String bpmnElementId);

    void completeServiceTask(UUID activityId, List<ProcessVariable> variables);

    void completeUserTask(UUID activityId, List<ProcessVariable> variables);

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
     * Fires an interrupting timer boundary: if the host activity is still active it is cancelled
     * and flow continues from the boundary event's outgoing flows. Called by the timer scheduler.
     */
    void fireBoundaryTimer(UUID hostActivityId, String boundaryElementId);

    void resolveIncident(UUID incidentId, List<ProcessVariable> variables);

    UUID startProcessInstance(UUID parentProcessInstanceId, UUID processDefinitionId, List<ProcessVariable> variables);

    /**
     * Starts a new instance beginning at a specific start element (used by message/timer start
     * triggers, which begin at their own start node).
     */
    UUID startProcessInstanceFromStartEvent(UUID processDefinitionId, String startElementId, List<ProcessVariable> variables);
}
