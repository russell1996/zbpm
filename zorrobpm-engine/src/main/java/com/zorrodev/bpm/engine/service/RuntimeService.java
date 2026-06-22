package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.dto.CompleteTaskDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.dto.IdDTO;

import java.util.List;
import java.util.UUID;

public interface RuntimeService {

    IdDTO startProcessInstance(StartProcessInstanceDTO dto);

    IdDTO startProcessInstance(UUID parentProcessInstanceId, StartProcessInstanceDTO dto);

    IdDTO completeServiceTask(UUID id, List<ProcessVariable> variables);

    /** Reports a service-task failure (retries → incident); see {@link ActivityService#failServiceTask}.
     *  {@code retries} is optional (Camunda {@code failJob} semantics): null decrements by one. */
    IdDTO failServiceTask(UUID id, String errorMessage, Integer retries);

    IdDTO completeUserTask(UUID id, List<ProcessVariable> variables);

    IdDTO resolveIncident(UUID id, List<ProcessVariable> variables);
}
