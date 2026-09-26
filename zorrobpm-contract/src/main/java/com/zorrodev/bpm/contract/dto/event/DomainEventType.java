package com.zorrodev.bpm.contract.dto.event;

/**
 * Catalog of domain event types emitted by the engine (ADR-7).
 * Each type corresponds to a state change in the engine's domain model.
 */
public enum DomainEventType {
    PROCESS_INSTANCE_STARTED("process-instance.started"),
    PROCESS_INSTANCE_COMPLETED("process-instance.completed"),
    PROCESS_INSTANCE_CANCELLED("process-instance.cancelled"),
    ACTIVITY_COMPLETED("activity.completed"),
    USER_TASK_CREATED("user-task.created"),
    USER_TASK_COMPLETED("user-task.completed"),
    USER_TASK_ASSIGNED("user-task.assigned"),
    USER_TASK_UNASSIGNED("user-task.unassigned"),
    SERVICE_TASK_CREATED("service-task.created"),
    INCIDENT_RAISED("incident.raised"),
    INCIDENT_RESOLVED("incident.resolved"),
    /**
     * WO-REL-22 (B3): an outbox entry exhausted its retries and was quarantined
     * (status FAILED). Additive — existing consumers match on their own types.
     */
    OUTBOX_QUARANTINED("outbox.quarantined");

    private final String value;

    DomainEventType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
