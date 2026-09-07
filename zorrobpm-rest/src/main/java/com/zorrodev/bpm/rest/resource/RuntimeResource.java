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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class RuntimeResource implements RuntimeContract {

    private final IncidentRuntimeOperations incidentRuntimeOperations;
    private final ProcessInstanceRuntimeOperations processInstanceRuntimeOperations;
    private final ServiceTaskRuntimeOperations serviceTaskRuntimeOperations;
    private final UserTaskRuntimeOperations userTaskRuntimeOperations;

    @Override
    public IdDTO startProcessInstance(@Valid @RequestBody StartProcessInstanceDTO dto) {
        return processInstanceRuntimeOperations.startProcessInstance(dto);
    }

    @Override
    public IdDTO completeServiceTask(@PathVariable UUID id, @RequestBody CompleteTaskDTO dto) {
        return serviceTaskRuntimeOperations.completeServiceTask(id, dto);
    }

    @Override
    public IdDTO completeAdHocScopeJob(@PathVariable UUID id, @RequestBody AdHocJobResultDTO dto) {
        return serviceTaskRuntimeOperations.completeAdHocScopeJob(id, dto);
    }

    @Override
    public IdDTO failServiceTask(@PathVariable UUID id, @RequestBody FailServiceTaskDTO dto) {
        return serviceTaskRuntimeOperations.failServiceTask(id, dto);
    }

    @Override
    public IdDTO completeUserTask(@PathVariable UUID id, @RequestBody CompleteTaskDTO dto) {
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
    public IdDTO assignUserTask(@PathVariable UUID id, @RequestBody AssignUserTaskDTO dto) {
        return userTaskRuntimeOperations.assignUserTask(id, dto);
    }

    @Override
    public IdDTO resolveIncident(@PathVariable UUID id, @RequestBody ResolveIncidentDTO dto) {
        return incidentRuntimeOperations.resolveIncident(id, dto);
    }

    @Override
    public IdDTO cancelProcessInstance(@PathVariable UUID id) {
        return processInstanceRuntimeOperations.cancelProcessInstance(id);
    }
}
