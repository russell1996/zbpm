package com.zorrodev.bpm.engine.service.db;

import java.util.UUID;

/**
 * WO-DEBT-1b: домен ServiceTasks.
 */
public interface ServiceTaskDbOperations {

    void createServiceTask(UUID activityId);

    void createServiceTask(UUID activityId, int retriesRemaining, String job);

    void completeServiceTask(UUID serviceTaskId);

    void setServiceTaskRetries(UUID serviceTaskId, int retries);

    int decrementServiceTaskRetries(UUID serviceTaskId);
}
