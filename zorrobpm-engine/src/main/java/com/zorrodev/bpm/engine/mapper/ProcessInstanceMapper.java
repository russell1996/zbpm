package com.zorrodev.bpm.engine.mapper;

import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProcessInstanceMapper {

    private final ProcessDefinitionRepository processDefinitionRepository;

    public ProcessInstance toDTO(ProcessInstanceEntity entity) {
        ProcessInstance pi = new ProcessInstance();
        pi.setId(entity.getId());
        pi.setParentActivityId(entity.getParentActivityId());
        pi.setStartedAt(entity.getStartedAt());
        pi.setCompletedAt(entity.getCompletedAt());
        pi.setProcessDefinitionId(entity.getProcessDefinitionId());
        // resolve the definition's name/key/version so lists/detail can show a human label
        processDefinitionRepository.findById(entity.getProcessDefinitionId()).ifPresent(pd -> {
            pi.setProcessName(pd.getName());
            pi.setProcessKey(pd.getKey());
            pi.setProcessVersion(pd.getVersion());
        });
        return pi;
    }
}
