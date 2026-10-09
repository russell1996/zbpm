package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.RuntimeContract;
import com.zorrodev.bpm.contract.dto.AssignUserTaskDTO;
import com.zorrodev.bpm.contract.dto.AdHocJobResultDTO;
import com.zorrodev.bpm.contract.dto.CompleteTaskDTO;
import com.zorrodev.bpm.contract.dto.FailServiceTaskDTO;
import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.contract.dto.MessagePublishResultDTO;
import com.zorrodev.bpm.contract.dto.PublishMessageDTO;
import com.zorrodev.bpm.contract.dto.ResolveIncidentDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.ThrowErrorDTO;
import com.zorrodev.bpm.contract.dto.ThrowErrorResultDTO;
import com.zorrodev.bpm.engine.service.AdmissionLease;
import com.zorrodev.bpm.engine.service.ScriptService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class RuntimeResource implements RuntimeContract {

    private final IncidentRuntimeOperations incidentRuntimeOperations;
    private final ProcessInstanceRuntimeOperations processInstanceRuntimeOperations;
    private final ServiceTaskRuntimeOperations serviceTaskRuntimeOperations;
    private final UserTaskRuntimeOperations userTaskRuntimeOperations;
    private final MessageRuntimeOperations messageRuntimeOperations;
    /**
     * WO-ENG-35 (NEW2-16): двухфазный admission script-пула. Гейтятся ТОЛЬКО
     * входы, чей синхронный путь может дойти до FEEL-пула
     * ({@code submitToPool}/{@code runWithBudget}) — остальные идут напрямую,
     * иначе sustained-перегрузка script-пула роняла бы операции, которым пул
     * не нужен (WO-ENG-35 раунд 2, Б-2: cancel давал 503 живьём).
     *
     * <p>Таблица достижимости (сверена чтением кода + grep, см. отчёт §3):
     * гейтятся start/completeServiceTask/completeAdHocScopeJob/completeUserTask
     * (хвосты завершений → {@code FlowNavigator.proceedToOutgoing} →
     * {@code evaluateScript/evaluateExpression}), failServiceTask (хвост
     * → {@code resumeParkedCompensationThrowers} → {@code proceedToOutgoing}),
     * throwServiceTaskError ({@code ErrorEscalationThrower} →
     * {@code proceedToOutgoing} продолжения границы), publishMessage
     * ({@code CompletionService.signal} → {@code proceedToOutgoing} +
     * {@code triggerConditionalEvents}; message-start → новое исполнение),
     * resolveIncident ({@code IncidentService} → {@code executor.execute} —
     * повторное исполнение элемента со script/FEEL). НЕ гейтятся
     * claim/unclaim/assign (плоские записи {@code dbService.claim/unclaim/
     * assignUserTask}, ветка со слушателями только ставит внешний job в
     * очередь) и cancelProcessInstance (отмена строк + удаление подписок +
     * флаги; {@code CancelingPhaseService} без FEEL — grep пуст).
     * В {@code *OperationsImpl} ноль прямых FEEL-ссылок (grep пуст) — весь
     * FEEL ниже, в хвостах движка.
     */
    private final ScriptService scriptService;

    /**
     * WO-ENG-35: выполнить FEEL-достижимую операцию под лизой admission-гейта
     * (место в script-пуле — до транзакции сервиса). Только для 8 входов из
     * таблицы выше; script-free входы идут напрямую.
     */
    private <T> T gatedFeel(java.util.function.Supplier<T> op) {
        try (AdmissionLease ignored = scriptService.admitOutsideTx()) {
            return op.get();
        }
    }

    /**
     * WO-API-1 (API-1): create → 201 + Location (Location ставит имплементация,
     * статус — здесь: маппинг висит на этом методе, `@ResponseStatus` на
     * имплементации `*Operations` игнорируется роутером).
     */
    @Override
    @ResponseStatus(HttpStatus.CREATED)
    public IdDTO startProcessInstance(@Valid @RequestBody StartProcessInstanceDTO dto) {
        return gatedFeel(() -> processInstanceRuntimeOperations.startProcessInstance(dto));
    }

    @Override
    public IdDTO completeServiceTask(@PathVariable UUID id, @Valid @RequestBody CompleteTaskDTO dto) {
        return gatedFeel(() -> serviceTaskRuntimeOperations.completeServiceTask(id, dto));
    }

    @Override
    public IdDTO completeAdHocScopeJob(@PathVariable UUID id, @Valid @RequestBody AdHocJobResultDTO dto) {
        return gatedFeel(() -> serviceTaskRuntimeOperations.completeAdHocScopeJob(id, dto));
    }

    @Override
    public IdDTO failServiceTask(@PathVariable UUID id, @Valid @RequestBody FailServiceTaskDTO dto) {
        return gatedFeel(() -> serviceTaskRuntimeOperations.failServiceTask(id, dto));
    }

    @Override
    public ThrowErrorResultDTO throwServiceTaskError(@PathVariable UUID id, @Valid @RequestBody ThrowErrorDTO dto) {
        return gatedFeel(() -> serviceTaskRuntimeOperations.throwServiceTaskError(id, dto));
    }

    @Override
    public MessagePublishResultDTO publishMessage(@Valid @RequestBody PublishMessageDTO dto) {
        return gatedFeel(() -> messageRuntimeOperations.publishMessage(dto));
    }

    @Override
    public IdDTO completeUserTask(@PathVariable UUID id, @Valid @RequestBody CompleteTaskDTO dto) {
        return gatedFeel(() -> userTaskRuntimeOperations.completeUserTask(id, dto));
    }

    /**
     * WO-ENG-35 раунд 2 (Б-2): script-free — гейта нет. Путь:
     * {@code CompletionService.claimUserTask} — плоская запись
     * (ветка со слушателями только ставит внешний job в очередь, FEEL нет),
     * поэтому при забитом script-пуле claim отвечает как обычно, а не 503.
     */
    @Override
    public IdDTO claimUserTask(@PathVariable UUID id) {
        return userTaskRuntimeOperations.claimUserTask(id);
    }

    /**
     * WO-ENG-35 раунд 2 (Б-2): script-free — гейта нет
     * ({@code dbService.unclaimUserTask}, плоская запись, FEEL нет).
     */
    @Override
    public IdDTO unclaimUserTask(@PathVariable UUID id) {
        return userTaskRuntimeOperations.unclaimUserTask(id);
    }

    /**
     * WO-ENG-35 раунд 2 (Б-2): script-free — гейта нет
     * ({@code CompletionService.assignUserTask} — плоская запись, как claim).
     */
    @Override
    public IdDTO assignUserTask(@PathVariable UUID id, @Valid @RequestBody AssignUserTaskDTO dto) {
        return userTaskRuntimeOperations.assignUserTask(id, dto);
    }

    @Override
    public IdDTO resolveIncident(@PathVariable UUID id, @Valid @RequestBody ResolveIncidentDTO dto) {
        return gatedFeel(() -> incidentRuntimeOperations.resolveIncident(id, dto));
    }

    /**
     * WO-API-1 (API-1): cancel асинхронен (eventual) → 202 Accepted, не голый 200.
     *
     * <p>WO-ENG-35 раунд 2 (Б-2): script-free — гейта нет. Путь: authz + lock +
     * отмена строк + удаление подписок/джобов + флаги
     * ({@code CancelingPhaseService} без FEEL — grep пуст), поэтому при забитом
     * script-пуле cancel отвечает 202, а не 503 (поймано красной командой
     * живьём: status=503 на гейтованном cancel).
     */
    @Override
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IdDTO cancelProcessInstance(@PathVariable UUID id) {
        return processInstanceRuntimeOperations.cancelProcessInstance(id);
    }
}
