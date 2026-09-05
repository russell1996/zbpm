package com.zorrodev.bpm.engine.service.db;

import java.util.UUID;

/**
 * WO-DEBT-1b: домен UserTasks.
 */
public interface UserTaskDbOperations {

    void createUserTask(UUID activityId, String assignee, String candidateGroups, String formKey, String formId, String dueDate, String followUpDate);

    /**
     * WO-C8-21: opens the creating-listener phase — writes the durable phase-marker row
     * (index 0, task fields still null). Emits NO created event: the task does not exist yet.
     */
    void startCreatingPhase(UUID activityId);

    /**
     * WO-C8-21: closes the creating-listener phase — fills the resolved task fields, clears
     * the index and emits the created event. This IS the task creation for phased elements.
     */
    void finishUserTaskCreation(UUID activityId, String assignee, String candidateGroups, String formKey, String formId, String dueDate, String followUpDate);

    void setPendingCreatingListenerIndex(UUID taskId, Integer index);

    Integer getPendingCreatingListenerIndex(UUID taskId);

    void completeUserTask(UUID serviceTaskId);

    void claimUserTask(UUID taskId, String assignee);

    void unclaimUserTask(UUID taskId);

    void assignUserTask(UUID taskId, String assignee);
}
