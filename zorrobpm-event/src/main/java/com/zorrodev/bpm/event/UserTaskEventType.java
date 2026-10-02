package com.zorrodev.bpm.event;

/** A change of a user task published to the {@code zorrobpm.user-task-events} exchange. */
public enum UserTaskEventType {
    CREATED,
    ASSIGNED,
    UNASSIGNED,
    COMPLETED,
    CANCELED;

    /** The routing key of the event: {@code user-task.<type>} in lower case. */
    public String routingKey() {
        return "user-task." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
