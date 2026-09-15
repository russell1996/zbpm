package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.RuntimeContract;
import com.zorrodev.bpm.contract.dto.AssignUserTaskDTO;
import com.zorrodev.bpm.contract.dto.AdHocJobResultDTO;
import com.zorrodev.bpm.contract.dto.CompleteTaskDTO;
import com.zorrodev.bpm.contract.dto.FailServiceTaskDTO;
import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.contract.dto.ResolveIncidentDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
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

    /**
     * WO-API-1 (API-1): create → 201 + Location (Location ставит имплементация,
     * статус — здесь: маппинг висит на этом методе, `@ResponseStatus` на
     * имплементации `*Operations` игнорируется роутером).
     */
    @Override
    @ResponseStatus(HttpStatus.CREATED)
    public IdDTO startProcessInstance(@Valid @RequestBody StartProcessInstanceDTO dto) {
        return processInstanceRuntimeOperations.startProcessInstance(dto);
    }

    @Override
    public IdDTO completeServiceTask(@PathVariable UUID id, @Valid @RequestBody CompleteTaskDTO dto) {
        return serviceTaskRuntimeOperations.completeServiceTask(id, dto);
    }

    @Override
    public IdDTO completeAdHocScopeJob(@PathVariable UUID id, @Valid @RequestBody AdHocJobResultDTO dto) {
        return serviceTaskRuntimeOperations.completeAdHocScopeJob(id, dto);
    }

    @Override
    public IdDTO failServiceTask(@PathVariable UUID id, @Valid @RequestBody FailServiceTaskDTO dto) {
        return serviceTaskRuntimeOperations.failServiceTask(id, dto);
    }

    @Override
    public IdDTO completeUserTask(@PathVariable UUID id, @Valid @RequestBody CompleteTaskDTO dto) {
        return userTaskRuntimeOperations.completeUserTask(id, dto);
    }

    @Override
    public IdDTO claimUserTask(@PathVariable UUID id) {
        return userTaskRuntimeOperations.claimUserTask(id);
    }

    @Override
    public IdDTO unclaimUserTask(@PathVariable UUID id) {
        return userTaskRuntimeOperations.unclaimUserTask(id);
    }

    @Override
    public IdDTO assignUserTask(@PathVariable UUID id, @Valid @RequestBody AssignUserTaskDTO dto) {
        return userTaskRuntimeOperations.assignUserTask(id, dto);
    }

    @Override
    public IdDTO resolveIncident(@PathVariable UUID id, @Valid @RequestBody ResolveIncidentDTO dto) {
        return incidentRuntimeOperations.resolveIncident(id, dto);
    }

    /**
     * WO-API-1 (API-1): cancel асинхронен (eventual) → 202 Accepted, не голый 200.
     */
    @Override
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IdDTO cancelProcessInstance(@PathVariable UUID id) {
        return processInstanceRuntimeOperations.cancelProcessInstance(id);
    }
}
