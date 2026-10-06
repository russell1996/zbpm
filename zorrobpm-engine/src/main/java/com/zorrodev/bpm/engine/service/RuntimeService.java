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
     * WO-C8-36 (CR-01): тот же complete, но с идентификатором вызова из сообщения
     * воркера ({@code dispatchPhase}/{@code dispatchIndex}, оба nullable).
     * Null-фаза = legacy без проверки (старый плоский метод — сюда с null/null).
     */
    IdDTO completeServiceTask(UUID id, List<ProcessVariable> variables, String dispatchPhase, Integer dispatchIndex);

    /**
     * WO-C8-33: completes a job-worker ad-hoc scope job with its structured result.
     * Additive (the flat {@link #completeServiceTask} is untouched).
     */
    IdDTO completeAdHocScopeJob(UUID id, com.zorrodev.bpm.contract.dto.AdHocJobResultDTO result);

    /** Reports a service-task failure (retries → incident); see {@link ActivityService#failServiceTask}.
     *  {@code retries} is optional (Camunda {@code failJob} semantics): null decrements by one. */
    IdDTO failServiceTask(UUID id, String errorMessage, Integer retries);

    /**
     * WO-C8-36 (CR-01): тот же fail, но с идентификатором вызова из сообщения
     * воркера (оба nullable; null-фаза = legacy без проверки).
     */
    IdDTO failServiceTask(UUID id, String errorMessage, Integer retries, String dispatchPhase, Integer dispatchIndex);

    /**
     * WO-C8-36 (red-team HOLD-1): тот же fail плюс идентификатор КОНКРЕТНОЙ отправки
     * результата ({@code completionId}, nullable). Нужен, чтобы дедуп дубликатов
     * открытой фазы был достижим из живого пути воркера (ServiceTaskCompleteListener
     * передаёт эхо из сообщения) — иначе перегрузка с ним существовала бы только
     * для прямых вызовов из тестов. Null = legacy без дедупа.
     */
    IdDTO failServiceTask(UUID id, String errorMessage, Integer retries, String dispatchPhase, Integer dispatchIndex,
        String completionId);

    /**
     * WO-DIFF-5: throws a BPMN error from a service task (boundary matching, not generic failure);
     * see {@link ActivityService#throwServiceTaskError}.
     */
    com.zorrodev.bpm.contract.dto.ThrowErrorResultDTO throwServiceTaskError(UUID id,
        com.zorrodev.bpm.contract.dto.ThrowErrorDTO dto);

    /**
     * WO-DIFF-5: publishes a message event into the correlation machinery;
     * see {@link ActivityService#publishMessage}.
     */
    com.zorrodev.bpm.contract.dto.MessagePublishResultDTO publishMessage(
        com.zorrodev.bpm.contract.dto.PublishMessageDTO dto);

    IdDTO completeUserTask(UUID id, List<ProcessVariable> variables);

    IdDTO resolveIncident(UUID id, List<ProcessVariable> variables);
}
