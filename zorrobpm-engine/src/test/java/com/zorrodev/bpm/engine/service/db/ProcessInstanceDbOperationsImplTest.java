package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.event.DomainEventEmitter;
import com.zorrodev.bpm.engine.mapper.ProcessInstanceMapper;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessInstanceDbOperationsImplTest {

    @Mock private ProcessInstanceRepository processInstanceRepository;
    @Mock private ProcessInstanceMapper processInstanceMapper;
    @Mock private VariableRepository variableRepository;
    // WO-ENG-16: стартовые переменные входят в историю (мок — поведение покрыто IT).
    @Mock private VariableHistoryWriter historyWriter;
    @Mock private DomainEventEmitter domainEventEmitter;
    @Mock private com.zorrodev.bpm.engine.metrics.BpmMetrics bpmMetrics;
    @Mock private CancelledScopeStateCleaner cancelledScopeStateCleaner;
    @Mock private ParallelGatewayDbOperations parallelGatewayDbOperations;
    /** WO-OPS-34: снятие instance-scoped подписок на терминальном переходе. */
    @Mock private FinishedInstanceSubscriptionCleaner finishedInstanceSubscriptionCleaner;
    @InjectMocks private ProcessInstanceDbOperationsImpl db;

    @Test
    void createProcessInstance_savesEntityAndVariablesAndEmits() {
        UUID parent = UUID.randomUUID(); UUID pdId = UUID.randomUUID();
        ProcessVariable pv = new ProcessVariable(); pv.setName("k"); pv.setValue("v"); pv.setType(com.zorrodev.bpm.contract.model.ProcessVariableType.STRING);
        UUID id = db.createProcessInstance(parent, pdId, List.of(pv));
        assertThat(id).isNotNull();
        ArgumentCaptor<ProcessInstanceEntity> captor = ArgumentCaptor.forClass(ProcessInstanceEntity.class);
        verify(processInstanceRepository).save(captor.capture());
        assertThat(captor.getValue().getParentActivityId()).isEqualTo(parent);
        assertThat(captor.getValue().getProcessDefinitionId()).isEqualTo(pdId);
        verify(variableRepository).saveAll(any(List.class));
        // WO-ENG-16: стартовые переменные — первая строка истории (источник INIT).
        verify(historyWriter).record(eq(id), eq(null), any(ProcessVariable.class), eq("INIT"));
        verify(domainEventEmitter).emitProcessInstanceStarted(eq(id), eq(pdId));
    }

    @Test
    void getProcessInstance_maps() {
        UUID id = UUID.randomUUID();
        ProcessInstanceEntity e = new ProcessInstanceEntity(); e.setId(id);
        ProcessInstance dto = new ProcessInstance(); dto.setId(id);
        when(processInstanceRepository.findById(id)).thenReturn(Optional.of(e));
        when(processInstanceMapper.toDTO(e)).thenReturn(dto);
        assertThat(db.getProcessInstance(id)).isEqualTo(dto);
    }

    @Test
    void lockProcessInstance_locks() {
        UUID id = UUID.randomUUID();
        ProcessInstanceEntity e = new ProcessInstanceEntity(); e.setId(id);
        when(processInstanceRepository.findByIdForUpdate(id)).thenReturn(Optional.of(e));
        db.lockProcessInstance(id);
        verify(processInstanceRepository).findByIdForUpdate(id);
    }

    @Test
    void completeProcessInstance_setsCompletedAndEmits() {
        UUID id = UUID.randomUUID(); UUID pdId = UUID.randomUUID();
        ProcessInstanceEntity e = new ProcessInstanceEntity(); e.setId(id); e.setProcessDefinitionId(pdId);
        when(processInstanceRepository.findById(id)).thenReturn(Optional.of(e));
        db.completeProcessInstance(id);
        verify(processInstanceRepository).setCompletedAt(eq(id), any(java.time.Instant.class));
        verify(domainEventEmitter).emitProcessInstanceCompleted(eq(id), eq(pdId));
    }

    /**
     * WO-C8-48: БЕЗ активной транзакционной синхронизации (прямой вызов вне
     * Spring-транзакции — unit/аварийные пути) зачистка выполняется немедленно,
     * как единственно возможное поведение. В транзакции прод-пути она уходит
     * в фазу коммита — см. {@link #completeProcessInstance_defersBookkeepingCleanupToCommitPhase}.
     */
    @Test
    void completeProcessInstance_withoutTransaction_clearsInstanceBookkeepingImmediately() {
        UUID id = UUID.randomUUID(); UUID pdId = UUID.randomUUID();
        ProcessInstanceEntity e = new ProcessInstanceEntity(); e.setId(id); e.setProcessDefinitionId(pdId);
        when(processInstanceRepository.findById(id)).thenReturn(Optional.of(e));
        db.completeProcessInstance(id);
        verify(parallelGatewayDbOperations).clearInstanceBookkeeping(id);
        var inOrder = org.mockito.Mockito.inOrder(
            parallelGatewayDbOperations, processInstanceRepository);
        inOrder.verify(parallelGatewayDbOperations).clearInstanceBookkeeping(id);
        inOrder.verify(processInstanceRepository).setCompletedAt(eq(id), any(java.time.Instant.class));
    }

    /**
     * WO-C8-48: отмена БЕЗ транзакционной синхронизации чистит немедленно
     * (fallback; в транзакции — фаза коммита).
     */
    @Test
    void cancelProcessInstance_withoutTransaction_clearsInstanceBookkeepingImmediately() {
        UUID id = UUID.randomUUID(); UUID pdId = UUID.randomUUID();
        ProcessInstanceEntity e = new ProcessInstanceEntity(); e.setId(id); e.setProcessDefinitionId(pdId);
        when(processInstanceRepository.findById(id)).thenReturn(Optional.of(e));
        db.cancelProcessInstance(id);
        verify(parallelGatewayDbOperations).clearInstanceBookkeeping(id);
    }

    /**
     * WO-C8-48 раунд 2: в АКТИВНОЙ транзакции зачистка НЕ выполняется в середине
     * хвоста деактивации — она регистрируется на фазу коммита. Терминальная пометка —
     * СЕРЕДИНА хвоста, а не конец: после неё в той же транзакции перепроверки
     * припаркованных join'ов ({@code EventTrigger.fireBoundary} → resume,
     * отложенный canceling-хвост {@code CompletionService.advanceOrFinishCanceling})
     * читают arrival-строки как кандидатов резюма; ранний DELETE терял их — 60/241
     * комбинаций матрицы C8-35 и живой wakeup-тест падали. Мутация «чистить сразу
     * в пометке» краснит этот тест (и сквозные классы).
     */
    @Test
    void completeProcessInstance_defersBookkeepingCleanupToCommitPhase() {
        UUID id = UUID.randomUUID(); UUID pdId = UUID.randomUUID();
        ProcessInstanceEntity e = new ProcessInstanceEntity(); e.setId(id); e.setProcessDefinitionId(pdId);
        when(processInstanceRepository.findById(id)).thenReturn(Optional.of(e));

        TransactionSynchronizationManager.initSynchronization();
        try {
            db.completeProcessInstance(id);

            verify(parallelGatewayDbOperations, never()).clearInstanceBookkeeping(id);
            List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations)
                .as("терминальный переход регистрирует ровно одну зачистку в фазе коммита")
                .hasSize(1);

            synchronizations.get(0).beforeCommit(false);
            verify(parallelGatewayDbOperations).clearInstanceBookkeeping(id);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** WO-C8-48 раунд 2: отмена — тот же механизм фазы коммита. */
    @Test
    void cancelProcessInstance_defersBookkeepingCleanupToCommitPhase() {
        UUID id = UUID.randomUUID(); UUID pdId = UUID.randomUUID();
        ProcessInstanceEntity e = new ProcessInstanceEntity(); e.setId(id); e.setProcessDefinitionId(pdId);
        when(processInstanceRepository.findById(id)).thenReturn(Optional.of(e));

        TransactionSynchronizationManager.initSynchronization();
        try {
            db.cancelProcessInstance(id);

            verify(parallelGatewayDbOperations, never()).clearInstanceBookkeeping(id);
            List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);
            synchronizations.get(0).beforeCommit(false);
            verify(parallelGatewayDbOperations).clearInstanceBookkeeping(id);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /**
     * WO-C8-48 раунд 2: откат транзакции зачистку не выполняет — откатывается и сама
     * терминальная пометка; инстанс не завершён, и строки ему ещё нужны. Гвардия
     * против реализации «чистить в afterCompletion без проверки статуса».
     */
    @Test
    void terminalTransition_rollback_doesNotClearBookkeeping() {
        UUID id = UUID.randomUUID(); UUID pdId = UUID.randomUUID();
        ProcessInstanceEntity e = new ProcessInstanceEntity(); e.setId(id); e.setProcessDefinitionId(pdId);
        when(processInstanceRepository.findById(id)).thenReturn(Optional.of(e));

        TransactionSynchronizationManager.initSynchronization();
        try {
            db.completeProcessInstance(id);
            List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);

            synchronizations.get(0).afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            verify(parallelGatewayDbOperations, never()).clearInstanceBookkeeping(id);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /**
     * WO-OPS-34: нормальное завершение снимает instance-scoped подписки инстанса
     * (message/signal) — и делает это ПОСЛЕ терминальной пометки. Порядок не
     * случайный: доставка, успевшая забрать подписку миллисекундой раньше, гасится
     * guard'ом «instance already completed» (EventTrigger.triggerEventSubprocess);
     * обратный порядок оставил бы окно, в котором инстанс ещё «жив», а
     * non-interrupting event-subprocess (она не consume-ит подписку, а зовёт
     * триггер напрямую) успела бы запустить handler в умирающем инстансе.
     */
    @Test
    void completeProcessInstance_dropsSubscriptionsAfterTerminalMark() {
        UUID id = UUID.randomUUID(); UUID pdId = UUID.randomUUID();
        ProcessInstanceEntity e = new ProcessInstanceEntity(); e.setId(id); e.setProcessDefinitionId(pdId);
        when(processInstanceRepository.findById(id)).thenReturn(Optional.of(e));

        db.completeProcessInstance(id);

        var inOrder = org.mockito.Mockito.inOrder(
            processInstanceRepository, finishedInstanceSubscriptionCleaner);
        inOrder.verify(processInstanceRepository).setCompletedAt(eq(id), any(java.time.Instant.class));
        inOrder.verify(finishedInstanceSubscriptionCleaner).clear(id);
    }

    @Test
    void cancelProcessInstance_setsCancelledAndCompletedAndEmits() {        UUID id = UUID.randomUUID(); UUID pdId = UUID.randomUUID();
        ProcessInstanceEntity e = new ProcessInstanceEntity(); e.setId(id); e.setProcessDefinitionId(pdId);
        when(processInstanceRepository.findById(id)).thenReturn(Optional.of(e));
        db.cancelProcessInstance(id);
        verify(processInstanceRepository).setCancelled(eq(id), eq(true));
        verify(processInstanceRepository).setCompletedAt(eq(id), any(java.time.Instant.class));
        verify(domainEventEmitter).emitProcessInstanceCancelled(eq(id), eq(pdId));
    }

    /**
     * WO-C8-46: отмена чистит скоуп-состояние ДО пометки cancelled, в той же
     * транзакции (единый choke-point; порядок важен — зачистка обязана видеть
     * строки до терминальной пометки).
     */
    @Test
    void cancelProcessInstance_clearsScopeStateBeforeMarkingCancelled() {
        UUID id = UUID.randomUUID(); UUID pdId = UUID.randomUUID();
        ProcessInstanceEntity e = new ProcessInstanceEntity(); e.setId(id); e.setProcessDefinitionId(pdId);
        when(processInstanceRepository.findById(id)).thenReturn(Optional.of(e));
        db.cancelProcessInstance(id);
        var inOrder = org.mockito.Mockito.inOrder(
            cancelledScopeStateCleaner, processInstanceRepository);
        inOrder.verify(cancelledScopeStateCleaner).clear(id);
        inOrder.verify(processInstanceRepository).setCancelled(eq(id), eq(true));
    }
}
