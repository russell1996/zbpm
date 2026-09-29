package com.zorrodev.bpm.engine.service.db;

import java.util.UUID;

/**
 * WO-DEBT-1b: домен ServiceTasks.
 */
public interface ServiceTaskDbOperations {

    void createServiceTask(UUID activityId);

    void createServiceTask(UUID activityId, int retriesRemaining, String job);

    /** WO-C8-11: creates the service task with a listener in flight (index into startListeners). */
    void createServiceTask(UUID activityId, int retriesRemaining, String job, Integer pendingListenerIndex);

    /** WO-C8-11: advances (or clears, with null) the in-flight listener; null = real job path. */
    void setPendingListenerIndex(UUID serviceTaskId, Integer pendingListenerIndex);

    /** WO-C8-11: reads the in-flight listener index; null = normal path. */
    Integer getPendingListenerIndex(UUID serviceTaskId);

    /** WO-C8-11b: advances (or clears, with null) the in-flight end-listener; null = end phase off. */
    void setPendingEndListenerIndex(UUID serviceTaskId, Integer pendingEndListenerIndex);

    /** WO-C8-11b: reads the in-flight end-listener index; null = end phase off. */
    Integer getPendingEndListenerIndex(UUID serviceTaskId);

    void completeServiceTask(UUID serviceTaskId);

    void setServiceTaskRetries(UUID serviceTaskId, int retries);

    int decrementServiceTaskRetries(UUID serviceTaskId);
}
