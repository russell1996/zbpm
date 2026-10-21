package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.event.DomainEventEmitter;
import com.zorrodev.bpm.engine.mapper.ProcessInstanceMapper;
import com.zorrodev.bpm.engine.metrics.BpmMetrics;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * WO-DEBT-1f: домен ProcessInstances — реализация.
 * Перенесено 1:1 из DBServiceImpl (5 методов).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProcessInstanceDbOperationsImpl implements ProcessInstanceDbOperations {

    private final ProcessInstanceRepository processInstanceRepository;
    private final ProcessInstanceMapper processInstanceMapper;
    private final BpmMetrics bpmMetrics;
    private final VariableRepository variableRepository;
    /** WO-ENG-16: стартовые переменные тоже входят в историю (источник INIT). */
    private final VariableHistoryWriter historyWriter;
    private final DomainEventEmitter domainEventEmitter;
    /**
     * WO-C8-46: model-aware половина зачистки отмены (outputCollection,
     * batch-маркеры живых MI-тел по BPMN-модели). Лениво, чтобы разорвать
     * construction-цикл BpmnService ↔ репозитории.
     */
    private final CancelledScopeStateCleaner cancelledScopeStateCleaner;
    /**
     * WO-C8-48: терминальный переход стирает bookkeeping join'ов инстанса (MI/ad-hoc
     * marker-строки и arrivals). Оба терминальных перехода ниже (complete/cancel)
     * проходят ТОЛЬКО через этот класс — единственная точка, где инстанс становится
     * завершённым (см. DBServiceImpl.completeProcessInstance/cancelProcessInstance);
     * отдельные хвостовые kill-пути (EventTrigger/ErrorEscalationThrower/EndEventHandler)
     * завершают ДОЧЕРНИЕ инстансы через тот же completeProcessInstance, а корневой —
     * через FlowNavigator → DBServiceImpl, то есть покрыты здесь же.
     * Зачистка идёт в фазе КОММИТА этой же транзакции — после хвостовых перепроверок
     * припаркованных join'ов (см. {@link #clearInstanceBookkeepingAtCommit}).
     */
    private final ParallelGatewayDbOperations parallelGatewayDbOperations;
    /**
     * WO-OPS-34: терминальный переход снимает instance-scoped подписки инстанса
     * (message/signal) — иначе завершённый инстанс продолжал «слушать»
     * сообщения, а broadcast сигнала после завершения уходил в мёртвый инстанс.
     * Вызов — ПОСЛЕ терминальной пометки (см. javadoc зачистки: доставка, успевшая
     * забрать подписку, гасится guard'ом «instance already completed»).
     * WO-OPS-38: тем же правилом закрыт и терминальный переход ОТМЕНЫ.
     */
    private final FinishedInstanceSubscriptionCleaner finishedInstanceSubscriptionCleaner;

    @Override
    public UUID createProcessInstance(UUID parentActivityId, UUID processDefinitionId, List<ProcessVariable> variables) {
        return createProcessInstance(parentActivityId, processDefinitionId, variables, null);
    }

    @Override
    public UUID createProcessInstance(UUID parentActivityId, UUID processDefinitionId,
            List<ProcessVariable> variables, String claimedInitiator) {
        UUID id = UUID.randomUUID();
        ProcessInstanceEntity entity = new ProcessInstanceEntity();
        entity.setId(id);
        entity.setProcessDefinitionId(processDefinitionId);
        entity.setStartedAt(Instant.now());
        entity.setParentActivityId(parentActivityId);
        // WO-API-1 (API-7): initiator в том же INSERT — атомарно со стартом.
        entity.setInitiator(claimedInitiator);
        processInstanceRepository.save(entity);
        // WO-AUDIT-12 (F-02): счётчики и gauge жизненного цикла инстанса живут
        // ЗДЕСЬ, а не в REST/_runtime-точке старта. Инкремент стоял в
        // RuntimeServiceImpl.startProcessInstance — то есть УЧИТЫВАЛСЯ только
        // «старт через runtime-сервис» (корневой инстанс), а декремент в
        // complete/cancelProcessInstance — ЛЮБОЙ инстанс. Асимметрия: gauge
        // активных инстансов уходил в минус на каждом дочернем инстансе
        // call activity (инкремента не было, декремент был) и на каждом
        // message/timer-старте. Теперь обе стороны живут на одной точке
        // (создание/терминальный переход) и считают ОДИН И ТОТ ЖЕ набор
        // инстансов: gauge = «сколько инстансов сейчас живых».
        bpmMetrics.processStarted();
        bpmMetrics.incrementActiveInstances();
        List<ProcessVariableEntity> vs = new LinkedList<>();
        for (ProcessVariable variable : Optional.ofNullable(variables).orElse(List.of())) {
            ProcessVariableEntity v = new ProcessVariableEntity();
            v.setId(UUID.randomUUID());
            v.setProcessInstanceId(id);
            v.setName(variable.getName());
            v.setType(variable.getType());
            v.setTextValue(variable.getValue() != null ? variable.getValue() : "");
            vs.add(v);
        }
        variableRepository.saveAll(vs);
        // WO-ENG-16: начальные значения — первая строка истории каждой
        // переменной (без этого история начиналась бы с первого изменения,
        // а исходное значение терялось бы — ровно тот пробел из WB-003).
        for (ProcessVariable variable : Optional.ofNullable(variables).orElse(List.of())) {
            historyWriter.record(id, null, variable, VariableHistoryWriter.SOURCE_INIT);
        }
        domainEventEmitter.emitProcessInstanceStarted(id, processDefinitionId);
        return id;
    }

    @Override
    public ProcessInstance getProcessInstance(UUID processInstanceId) {
        ProcessInstanceEntity entity = processInstanceRepository.findById(processInstanceId).orElseThrow();
        return processInstanceMapper.toDTO(entity);
    }

    @Override
    public void lockProcessInstance(UUID processInstanceId) {
        processInstanceRepository.findByIdForUpdate(processInstanceId).orElseThrow();
    }

    @Override
    public List<UUID> findRunningChildInstanceIds(UUID parentActivityId) {
        return processInstanceRepository.findByParentActivityId(parentActivityId).stream()
            .filter(pi -> pi.getCompletedAt() == null)
            .map(ProcessInstanceEntity::getId)
            .toList();
    }

    @Override
    public List<UUID> findRunningChildInstanceIdsByParentInstance(UUID processInstanceId) {
        return processInstanceRepository.findRunningChildrenByParentInstanceIdForUpdateSkipLocked(processInstanceId).stream()
            .map(ProcessInstanceEntity::getId)
            .toList();
    }

    @Override
    public void completeProcessInstance(UUID processInstanceId) {
        ProcessInstanceEntity pi = processInstanceRepository.findById(processInstanceId).orElseThrow();
        // WO-AUDIT-12 (F-02): терминальный переход — ОДИН раз на инстанс.
        // Повторный complete (например, гонка «ребёнок завершается» × «каскад
        // отмены убил ребёнка») не должен ни второй раз эмитить
        // processCompleted, ни второй раз сдвинуть gauge активных инстансов
        // (второй декремент = уход gauge в минус).
        if (pi.getCompletedAt() != null || pi.isCancelled()) {
            log.warn("completeProcessInstance({}): instance is already terminal (cancelled={}, completedAt={}) — "
                + "second completion ignored", processInstanceId, pi.isCancelled(), pi.getCompletedAt());
            return;
        }
        // WO-C8-48: завершённый инстанс не оставляет bookkeeping-строк join'ов
        // (arrival + marker). Именно здесь, а не в kill-хуке
        // finalizeFinishedInstance: нормальное завершение ad-hoc/MI-скоупов
        // (cancel-end, error-walk, terminate внутри скоупа) терминирует инстанс
        // ЭТИМ путём, и marker-строки их join'ов после этого не читает никто.
        // Зачистка — в фазе коммита этой же транзакции, НЕ до хвостовых
        // перепроверок припаркованных join'ов (см. clearInstanceBookkeepingAtCommit).
        clearInstanceBookkeepingAtCommit(processInstanceId);
        processInstanceRepository.setCompletedAt(processInstanceId, Instant.now());
        // WO-OPS-34: подписки инстанса умирают вместе с ним. Инстанс мёртв —
        // доставка ему не нужна (Zeebe 8.5 снимает подписку вместе со scope), а
        // сообщение, пришедшее после завершения, не должно уходить в мёртвый
        // инстанс. Порядок — ПОСЛЕ терминальной пометки: доставка, успевшая
        // забрать подписку миллисекундой раньше, гасится guard'ом
        // «instance already completed» (EventTrigger.triggerEventSubprocess).
        finishedInstanceSubscriptionCleaner.clear(processInstanceId);
        domainEventEmitter.emitProcessInstanceCompleted(processInstanceId, pi.getProcessDefinitionId());
        // WO-OBS-1: single choke for every completion path (normal end, escalation end).
        bpmMetrics.processCompleted();
        bpmMetrics.decrementActiveInstances();
    }

    @Override
    public void cancelProcessInstance(UUID processInstanceId) {
        ProcessInstanceEntity pi = processInstanceRepository.findById(processInstanceId).orElseThrow();
        // WO-AUDIT-12 (F-02): отмена — тоже терминальный переход, ровно один.
        // Повторная отмена (каскад × внешний вызов) не должна ни второй раз
        // эмитить processInstanceCancelled, ни второй раз сдвинуть gauge.
        if (pi.getCompletedAt() != null || pi.isCancelled()) {
            log.warn("cancelProcessInstance({}): instance is already terminal (cancelled={}, completedAt={}) — "
                + "second cancel ignored", processInstanceId, pi.isCancelled(), pi.getCompletedAt());
            return;
        }
        // WO-C8-46: скоуп-переменные ДО пометки cancelled — та же транзакция,
        // та же зачистка, что хвосты завершения (item/loopCounter MI-инстансов,
        // предсозданный outputCollection, batch-маркеры входа).
        clearCancelledScopeState(processInstanceId);
        // WO-C8-48: bookkeeping join'ов (arrival + marker) — в фазе коммита той же
        // транзакции, единым с complete-путём механизмом (любой будущий резюм после
        // отмены в этой транзакции ещё увидит строки; наружу инстанс уходит без них).
        clearInstanceBookkeepingAtCommit(processInstanceId);
        processInstanceRepository.setCancelled(processInstanceId, true);
        processInstanceRepository.setCompletedAt(processInstanceId, Instant.now());
        // WO-OPS-38: подписки умирают вместе с инстансом — НА ТОМ ЖЕ правиле,
        // что и при нормальном завершении (см. completeProcessInstance выше).
        // Раньше отмена снимала ТОЛЬКО message-подписки (кил-путь и операторская
        // отмена — по отдельности), а signal-подписки переживали смерть инстанса:
        // broadcast сигнала уходил в мёртвый инстанс, а инвариант утечек (G-P)
        // видел остаток типа «subscription».
        //
        // Почему ЗДЕСЬ, а не в каждом пути отмены: этот метод — единственная точка,
        // где инстанс становится терминальным ПОСЛЕ отмены. Через неё проходят
        // и kill-путь (InstanceCancellationService.kill → processInstanceDbOperations
        // .cancelProcessInstance), и операторская отмена (DBServiceImpl
        // .cancelProcessInstance), и отложенный хвост canceling-фазы
        // (CompletionService). Одно правило вместо трёх копий.
        //
        // Порядок — ПОСЛЕ терминальной пометки (как в completeProcessInstance):
        // доставка, успевшая забрать подписку миллисекундой раньше, гасится
        // guard'ом «instance already completed» (EventTrigger.triggerEventSubprocess).
        finishedInstanceSubscriptionCleaner.clear(processInstanceId);
        domainEventEmitter.emitProcessInstanceCancelled(processInstanceId, pi.getProcessDefinitionId());
        // WO-OBS-1: cancel ends the instance too (gauge must not leak); not a failure.
        bpmMetrics.decrementActiveInstances();
    }

    @Override
    public void clearCancelledScopeState(UUID processInstanceId) {
        cancelledScopeStateCleaner.clear(processInstanceId);
    }

    /**
     * WO-C8-48 раунд 2: зачистка bookkeeping-строк join'ов — в фазе КОММИТА
     * терминальной транзакции, а не в её середине.
     *
     * <p>Почему не сразу: терминальная пометка — СЕРЕДИНА хвоста деактивации, а не его
     * конец. В той же транзакции после неё ещё работают штатные перепроверки
     * припаркованных inclusive-join'ов (шаг 3/B2 WO-C8-35), и arrival-строки — их
     * источник кандидатов: interrupting boundary доводит поток границы
     * ({@code EventTrigger.fireBoundary} → {@code proceedToOutgoing} → …
     * → {@code FlowNavigator.finishBranch}, который и вызывает
     * complete/cancelProcessInstance) и только ЗАТЕМ зовёт
     * {@code resumeParkedInclusiveJoins}; отложенный canceling-хвост
     * {@code CompletionService.advanceOrFinishCanceling} устроен тем же порядком.
     * На base резюм видел строки и доставлял припаркованный join ПОСЛЕ терминальной
     * пометки — это закреплено матрицей C8-35 (241/241). Ранний DELETE в самой пометке
     * съедал кандидатов резюма ({@code getGatewaysWithOpenArrivals}), и join тихо терял
     * продолжение: 60/241 комбинаций матрицы и живой wakeup-тест падали (RED дословно —
     * в отчёте WO-C8-48). Резюм — живой потребитель строк, поэтому «без изменения
     * поведения живых join'ов» означает: строки обязаны дожить до конца хвоста
     * деактивации.
     *
     * <p>Коммит — единственная точка, где хвост деактивации гарантированно закончен:
     * любой последователь в той же транзакции (резюм, диагностика, тест) видит
     * состояние как на base, а наружу терминальный инстанс уходит без единой строки.
     * Откат транзакции зачистку не выполняет — и это верно: откатывается и сама
     * терминальная пометка, инстанс не завершён и строки ему ещё нужны.
     *
     * <p>Транзакционной синхронизации нет (прямой вызов вне Spring-транзакции —
     * юнит-тесты, аварийные пути без tx): чистим немедленно, как раньше.
     */
    private void clearInstanceBookkeepingAtCommit(UUID processInstanceId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            parallelGatewayDbOperations.clearInstanceBookkeeping(processInstanceId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                parallelGatewayDbOperations.clearInstanceBookkeeping(processInstanceId);
            }
        });
    }
}
