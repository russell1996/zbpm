package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.engine.dto.MessageSubscription;
import com.zorrodev.bpm.engine.dto.MessageStartSubscription;

import java.util.List;
import java.util.UUID;

/**
 * WO-DEBT-1b: домен MessageSubscriptions.
 */
public interface MessageSubscriptionDbOperations {

    UUID createMessageSubscription(UUID processInstanceId, UUID activityId, String messageName);

    UUID createMessageSubscription(UUID processInstanceId, UUID activityId, String messageName, String boundaryElementId);

    UUID createMessageSubscription(UUID processInstanceId, UUID activityId, String messageName, String boundaryElementId, String correlationKey);

    UUID createEventSubprocessMessageSubscription(UUID processInstanceId, String messageName, String eventSubprocessId);

    void createMessageStartSubscription(String processKey, UUID processDefinitionId, String elementId, String messageName);

    void deleteMessageStartSubscriptionsByKey(String processKey);

    void deleteMessageSubscriptionsByProcessInstanceId(UUID processInstanceId);

    List<MessageStartSubscription> findMessageStartSubscriptions(String messageName);

    List<MessageSubscription> findMessageSubscriptions(String messageName, UUID processInstanceId);

    /**
     * WO-REL-31 CR-3: keyset-paged variant — returns up to {@code FAN_OUT_BATCH_SIZE} (500)
     * unconsumed subscriptions in deterministic id-DESC order. Cursor = minimum id from previous
     * page (null = first page).
     */
    List<MessageSubscription> findMessageSubscriptions(String messageName, UUID processInstanceId, UUID cursorId);

    List<MessageSubscription> findMessageSubscriptionsByKey(String messageName, String correlationKey);

    /** Keyset-paged variant — batch ≤500, id-DESC order, cursor = previous page min id. */
    List<MessageSubscription> findMessageSubscriptionsByKey(String messageName, String correlationKey, UUID cursorId);

    boolean consumeMessageSubscription(UUID subscriptionId);
}
