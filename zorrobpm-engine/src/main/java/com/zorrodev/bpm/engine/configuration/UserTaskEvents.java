package com.zorrodev.bpm.engine.configuration;

/**
 * Properties of the user task lifecycle events ({@code engine-user-task-events}). The flag is read by
 * the engine (writing the outbox), the RabbitMQ module (publishing it) and the gRPC transport (keeping
 * RabbitMQ on).
 */
public final class UserTaskEvents {

    public static final String PREFIX = "zorrobpm.events.user-task";
    /** {@code true} turns the events on; off when not set. */
    public static final String ENABLED_PROPERTY = PREFIX + ".enabled";
    /** {@code false} keeps the outbox written but not published, for tests. */
    public static final String RELAY_ENABLED_PROPERTY = PREFIX + ".relay-enabled";

    private UserTaskEvents() {
    }

    public static boolean enabled(String value) {
        return "true".equalsIgnoreCase(value == null ? null : value.trim());
    }
}
