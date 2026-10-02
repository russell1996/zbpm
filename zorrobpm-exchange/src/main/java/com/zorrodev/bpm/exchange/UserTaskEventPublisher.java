package com.zorrodev.bpm.exchange;

import java.util.List;

/**
 * Publishes user task events to the broker in the given order. Returns only once the broker has
 * confirmed every message; any exception means the batch is not confirmed and will be sent again.
 */
public interface UserTaskEventPublisher {

    void publish(List<UserTaskEventMessage> messages);
}
