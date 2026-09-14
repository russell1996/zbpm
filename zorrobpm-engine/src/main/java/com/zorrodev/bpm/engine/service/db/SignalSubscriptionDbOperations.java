package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.engine.dto.SignalSubscription;
import com.zorrodev.bpm.engine.dto.SignalStartSubscription;

import java.util.List;
import java.util.UUID;

/**
 * WO-DEBT-1b: домен SignalSubscriptions.
 */
public interface SignalSubscriptionDbOperations {

    UUID createSignalSubscription(UUID processInstanceId, UUID activityId, String signalName);

    UUID createSignalSubscription(UUID processInstanceId, UUID activityId, String signalName, String boundaryElementId);

    UUID createEventSubprocessSignalSubscription(UUID processInstanceId, String signalName, String eventSubprocessId);

    void createSignalStartSubscription(String processKey, UUID processDefinitionId, String elementId, String signalName);

    void deleteSignalStartSubscriptionsByKey(String processKey);

    List<SignalStartSubscription> findSignalStartSubscriptions(String signalName);

    List<SignalSubscription> findSignalSubscriptions(String signalName);

    /**
     * WO-REL-31 CR-3: keyset-paged variant — returns up to {@code FAN_OUT_BATCH_SIZE} (500)
     * unconsumed subscriptions in deterministic id-DESC order.
     */
    List<SignalSubscription> findSignalSubscriptions(String signalName, UUID cursorId);

    boolean consumeSignalSubscription(UUID subscriptionId);
}
