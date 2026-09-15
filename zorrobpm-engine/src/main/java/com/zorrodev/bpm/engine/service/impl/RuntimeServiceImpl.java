package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.dto.CompleteTaskDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.dto.IdDTO;
import com.zorrodev.bpm.engine.metrics.BpmMetrics;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * WO-REL-30: доменная транзакционная граница. Каждый публичный метод — одна
 * транзакция: create + setVars + createToken + execute либо коммитятся целиком,
 * либо катятся целиком. REQUIRED: REST-фасады и шедулеры (своя Tx уже есть)
 * джойнятся без смены поведения; прямые вызовы без внешней Tx (тесты, будущие
 * пути) получают свою границу вместо автокоммита по стейтментам.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class RuntimeServiceImpl implements RuntimeService {

    private final DBService dbService;
    private final ActivityService activityService;
    private final BpmnService bpmnService;
    private final BpmMetrics bpmMetrics;

    @Override
    public IdDTO startProcessInstance(StartProcessInstanceDTO dto) {
        return startProcessInstance(null, dto);
    }

    @Override
    public IdDTO startProcessInstance(UUID parentProcessInstanceId, StartProcessInstanceDTO dto) {
        return startProcessInstance(parentProcessInstanceId, dto, null);
    }

    /**
     * WO-API-1 (API-7): перегрузка с initiator — та же транзакция, тот же путь,
     * только create несёт initiator сразу. Старый двухаргументный метод —
     * делегация с null (поведение побайтово).
     */
    @Override
    public IdDTO startProcessInstance(StartProcessInstanceDTO dto, String claimedInitiator) {
        return startProcessInstance(null, dto, claimedInitiator);
    }

    private IdDTO startProcessInstance(UUID parentProcessInstanceId, StartProcessInstanceDTO dto,
            String claimedInitiator) {
        UUID processDefinitionId = dto.getProcessDefinitionId();
        if (processDefinitionId == null) {
            String key = dto.getProcessDefinitionKey();
            Integer version = dto.getProcessDefinitionVersion();
            if (version == null) {
                version = dbService.getMaxProcessDefinitionVersionByKey(key);
            }
            ProcessDefinition pd = dbService.getProcessDefinition(key, version);
            processDefinitionId = pd.getId();
        }
        List<ProcessVariable> variables = dto.getVariables();

        UUID processInstanceId = activityService.startProcessInstance(
            parentProcessInstanceId, processDefinitionId, variables, claimedInitiator);
        bpmMetrics.processStarted();
        bpmMetrics.incrementActiveInstances();

        IdDTO result = new IdDTO();
        result.setId(processInstanceId);
        return result;
    }

    @Override
    public IdDTO completeServiceTask(UUID id, List<ProcessVariable> variables) {
        activityService.completeServiceTask(id, variables);
        IdDTO result = new IdDTO();
        result.setId(id);
        return result;
    }

    @Override
    public IdDTO completeAdHocScopeJob(UUID id, com.zorrodev.bpm.contract.dto.AdHocJobResultDTO result) {
        activityService.completeAdHocScopeJob(id, result);
        IdDTO dto = new IdDTO();
        dto.setId(id);
        return dto;
    }

    @Override
    public IdDTO failServiceTask(UUID id, String errorMessage, Integer retries) {
        activityService.failServiceTask(id, errorMessage, retries);
        IdDTO result = new IdDTO();
        result.setId(id);
        return result;
    }

    @Override
    public IdDTO completeUserTask(UUID id, List<ProcessVariable> variables) {
        activityService.completeUserTask(id, variables);
        IdDTO result = new IdDTO();
        result.setId(id);
        return result;
    }

    @Override
    public IdDTO resolveIncident(UUID id, List<ProcessVariable> variables) {
        activityService.resolveIncident(id, variables);
        IdDTO result = new IdDTO();
        result.setId(id);
        return result;
    }
}
