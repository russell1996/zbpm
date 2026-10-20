package com.zorrodev.bpm.engine.service.db;

import java.util.UUID;

/**
 * WO-DEBT-1b: домен UserTasks.
 */
public interface UserTaskDbOperations {

    void createUserTask(UUID activityId, String assignee, String candidateGroups, String candidateUsers, String formKey, String formId, String bindingType, String dueDate, String followUpDate, Integer priority);

    void completeUserTask(UUID serviceTaskId);

    /**
     * WO-C8-57 (F-15): закрывает job-строку ТЕРМИНИРОВАННОГО инстанса (MI-сиблинг,
     * сработал completionCondition). Null-safe (строки может не быть — creating-фаза
     * C8-21) и БЕЗ события «completed»: инстанс терминирован, а не завершён.
     */
    void terminateUserTask(UUID userTaskId);

    void claimUserTask(UUID taskId, String assignee);

    void unclaimUserTask(UUID taskId);

    void assignUserTask(UUID taskId, String assignee);
}
