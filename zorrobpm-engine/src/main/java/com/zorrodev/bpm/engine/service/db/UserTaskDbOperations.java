package com.zorrodev.bpm.engine.service.db;

import java.util.UUID;

/**
 * WO-DEBT-1b: домен UserTasks.
 */
public interface UserTaskDbOperations {

    void createUserTask(UUID activityId, String assignee, String candidateGroups, String formKey, String formId, String bindingType, String dueDate, String followUpDate, Integer priority);

    void completeUserTask(UUID serviceTaskId);

    void claimUserTask(UUID taskId, String assignee);

    void unclaimUserTask(UUID taskId);

    void assignUserTask(UUID taskId, String assignee);
}
