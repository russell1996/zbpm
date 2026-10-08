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
     * WO-ENG-35 (NEW2-16): двухфазный admission script-пула. Каждый вход ждёт
     * место в пуле ЗДЕСЬ (до транзакции сервиса) и держит лизу всё время
     * операции: sustained-перегрузка даёт тот же 503, но не удерживает
     * соединение из Hikari-пула всё окно ожидания. Контроллер — самый внешний
     * слой (транзакции открываются глубже, в сервисах), поэтому гейт один на
     * все 12 входов, а не копипастой в 5 *Operations-бинах.
     */
    private final ScriptService scriptService;

    /** WO-ENG-35: выполнить операцию под лизой admission-гейта (см. поле). */
    private <T> T gated(java.util.function.Supplier<T> op) {
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
        return gated(() -> processInstanceRuntimeOperations.startProcessInstance(dto));
    }

    @Override
    public IdDTO completeServiceTask(@PathVariable UUID id, @Valid @RequestBody CompleteTaskDTO dto) {
        return gated(() -> serviceTaskRuntimeOperations.completeServiceTask(id, dto));
    }

    @Override
    public IdDTO completeAdHocScopeJob(@PathVariable UUID id, @Valid @RequestBody AdHocJobResultDTO dto) {
        return gated(() -> serviceTaskRuntimeOperations.completeAdHocScopeJob(id, dto));
    }

    @Override
    public IdDTO failServiceTask(@PathVariable UUID id, @Valid @RequestBody FailServiceTaskDTO dto) {
        return gated(() -> serviceTaskRuntimeOperations.failServiceTask(id, dto));
    }

    @Override
    public ThrowErrorResultDTO throwServiceTaskError(@PathVariable UUID id, @Valid @RequestBody ThrowErrorDTO dto) {
        return gated(() -> serviceTaskRuntimeOperations.throwServiceTaskError(id, dto));
    }

    @Override
    public MessagePublishResultDTO publishMessage(@Valid @RequestBody PublishMessageDTO dto) {
        return gated(() -> messageRuntimeOperations.publishMessage(dto));
    }

    @Override
    public IdDTO completeUserTask(@PathVariable UUID id, @Valid @RequestBody CompleteTaskDTO dto) {
        return gated(() -> userTaskRuntimeOperations.completeUserTask(id, dto));
    }

    @Override
    public IdDTO claimUserTask(@PathVariable UUID id) {
        return gated(() -> userTaskRuntimeOperations.claimUserTask(id));
    }

    @Override
    public IdDTO unclaimUserTask(@PathVariable UUID id) {
        return gated(() -> userTaskRuntimeOperations.unclaimUserTask(id));
    }

    @Override
    public IdDTO assignUserTask(@PathVariable UUID id, @Valid @RequestBody AssignUserTaskDTO dto) {
        return gated(() -> userTaskRuntimeOperations.assignUserTask(id, dto));
    }

    @Override
    public IdDTO resolveIncident(@PathVariable UUID id, @Valid @RequestBody ResolveIncidentDTO dto) {
        return gated(() -> incidentRuntimeOperations.resolveIncident(id, dto));
    }

    /**
     * WO-API-1 (API-1): cancel асинхронен (eventual) → 202 Accepted, не голый 200.
     */
    @Override
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IdDTO cancelProcessInstance(@PathVariable UUID id) {
        return gated(() -> processInstanceRuntimeOperations.cancelProcessInstance(id));
    }
}
