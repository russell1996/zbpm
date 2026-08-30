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

    boolean consumeSignalSubscription(UUID subscriptionId);
}
