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
    SERVICE_TASK_CREATED("service-task.created"),
    INCIDENT_RAISED("incident.raised"),
    INCIDENT_RESOLVED("incident.resolved");

    private final String value;

    DomainEventType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
