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

    List<MessageSubscription> findMessageSubscriptionsByKey(String messageName, String correlationKey);

    boolean consumeMessageSubscription(UUID subscriptionId);
}
