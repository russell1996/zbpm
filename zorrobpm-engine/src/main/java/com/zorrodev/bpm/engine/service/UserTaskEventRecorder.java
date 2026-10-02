package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.event.UserTaskEventType;

import java.time.Instant;

/**
 * Writes a user task lifecycle event to the outbox in the transaction of the change, when the events
 * are on ({@code zorrobpm.events.user-task.enabled}). The task is passed in its state after the change.
 */
public interface UserTaskEventRecorder {

    void record(UserTaskEntity task, UserTaskEventType type, Instant occurredAt);
}
