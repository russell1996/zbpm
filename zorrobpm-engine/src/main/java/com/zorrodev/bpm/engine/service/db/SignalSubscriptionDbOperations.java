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

    /**
     * WO-C8-35 (CR-09, ШАГ 2/B1): unconsumed signal subscriptions of this instance — the caller
     * keeps only the boundary / event-sub-process forms (the ones that continue elsewhere).
     */
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
