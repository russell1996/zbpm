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

    /**
     * WO-API-1 (API-7): старт с initiator в том же вызове/транзакции — create
     * пишет initiator в том же INSERT, без второго save после. Null =
     * без инициатора (старое поведение побайтово).
     */
    IdDTO startProcessInstance(StartProcessInstanceDTO dto, String claimedInitiator);

    IdDTO completeServiceTask(UUID id, List<ProcessVariable> variables);

    /**
     * WO-C8-33: completes a job-worker ad-hoc scope job with its structured result.
     * Additive (the flat {@link #completeServiceTask} is untouched).
     */
    IdDTO completeAdHocScopeJob(UUID id, com.zorrodev.bpm.contract.dto.AdHocJobResultDTO result);

    /** Reports a service-task failure (retries → incident); see {@link ActivityService#failServiceTask}.
     *  {@code retries} is optional (Camunda {@code failJob} semantics): null decrements by one. */
    IdDTO failServiceTask(UUID id, String errorMessage, Integer retries);

    IdDTO completeUserTask(UUID id, List<ProcessVariable> variables);

    IdDTO resolveIncident(UUID id, List<ProcessVariable> variables);
}
