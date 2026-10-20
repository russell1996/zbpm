package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;

import java.util.List;
import java.util.UUID;

/**
 * WO-DEBT-1b: домен ProcessInstances.
 */
public interface ProcessInstanceDbOperations {

    UUID createProcessInstance(UUID parentActivityId, UUID processDefinitionId, List<ProcessVariable> variables);

    /**
     * WO-API-1 (API-7): тот же create, но initiator пишется в том же INSERT —
     * вместо второго save после старта. Null = без инициатора (старый путь).
     */
    UUID createProcessInstance(UUID parentActivityId, UUID processDefinitionId,
        List<ProcessVariable> variables, String claimedInitiator);

    ProcessInstance getProcessInstance(UUID processInstanceId);

    void completeProcessInstance(UUID processInstanceId);

    void cancelProcessInstance(UUID processInstanceId);

    /**
     * WO-C8-46: единая зачистка скоуп-переменных при отмене инстанса — тем же
     * механизмом, что хвосты нормального завершения ({@code deleteVariables} по
     * скоупу каждой отменённой/снятой activity + {@code deleteMiBodyState} по
     * каждому отменённому MI-телу). Root-переменные не трогает, историю не
     * пишет (lifecycle-мусор, как {@code deleteVariables}). Вызывается из
     * {@code cancelProcessInstance} ДО пометки cancelled — та же транзакция.
     */
    void clearCancelledScopeState(UUID processInstanceId);

    void lockProcessInstance(UUID processInstanceId);

    /**
     * WO-C8-34 (CR-04): ids of still-running child instances started by one call
     * activity row (completed ones excluded — nothing to cancel there).
     */
    List<UUID> findRunningChildInstanceIds(UUID parentActivityId);

    /**
     * WO-AUDIT-12 (F-02): ids всех ещё НЕ терминальных дочерних инстансов
     * (call activity) данного инстанса — рекурсивная каскадная отмена берёт
     * отсюда следующий уровень дерева.
     */
    List<UUID> findRunningChildInstanceIdsByParentInstance(UUID processInstanceId);
}
