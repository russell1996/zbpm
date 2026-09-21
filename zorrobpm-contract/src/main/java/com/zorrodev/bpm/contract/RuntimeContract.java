package com.zorrodev.bpm.contract;

import com.zorrodev.bpm.contract.dto.AdHocJobResultDTO;
import com.zorrodev.bpm.contract.dto.CompleteTaskDTO;
import com.zorrodev.bpm.contract.dto.FailServiceTaskDTO;
import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.contract.dto.MessagePublishResultDTO;
import com.zorrodev.bpm.contract.dto.PublishMessageDTO;
import com.zorrodev.bpm.contract.dto.ResolveIncidentDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.AssignUserTaskDTO;
import com.zorrodev.bpm.contract.dto.ThrowErrorDTO;
import com.zorrodev.bpm.contract.dto.ThrowErrorResultDTO;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

import java.util.UUID;

public interface RuntimeContract {

    @PostExchange("/process-instances")
    IdDTO startProcessInstance(@RequestBody StartProcessInstanceDTO dto);

    @PostExchange("/service-tasks/{id}/complete")
    IdDTO completeServiceTask(@PathVariable UUID id, @RequestBody CompleteTaskDTO dto);

    /**
     * WO-C8-33: completes a job-worker ad-hoc scope job with its structured result
     * (activateElements[] + flags). Additive — the flat complete above is untouched.
     */
    @PostExchange("/service-tasks/{id}/complete-adhoc")
    IdDTO completeAdHocScopeJob(@PathVariable UUID id, @RequestBody AdHocJobResultDTO dto);

    /** Reports a service-task failure: decrements retries; raises an incident with {@code message} at 0. */
    @PostExchange("/service-tasks/{id}/fail")
    IdDTO failServiceTask(@PathVariable UUID id, @RequestBody FailServiceTaskDTO dto);

    /**
     * WO-DIFF-5: throws a BPMN error with {@code errorCode} from a service task, with BPMN
     * error-boundary matching (not a generic failure). G-C approved 2026-09-21.
     */
    @PostExchange("/service-tasks/{id}/throw-error")
    ThrowErrorResultDTO throwServiceTaskError(@PathVariable UUID id, @RequestBody ThrowErrorDTO dto);

    /**
     * WO-DIFF-5: publishes a message event (name + optional correlation key/instance +
     * variables) into the engine's correlation machinery. G-C approved 2026-09-21.
     */
    @PostExchange("/messages/publish")
    MessagePublishResultDTO publishMessage(@RequestBody PublishMessageDTO dto);

    @PostExchange("/user-tasks/{id}/complete")
    IdDTO completeUserTask(@PathVariable UUID id, @RequestBody CompleteTaskDTO dto);

    @PostExchange("/user-tasks/{id}/claim")
    IdDTO claimUserTask(@PathVariable UUID id);

    @PostExchange("/user-tasks/{id}/unclaim")
    IdDTO unclaimUserTask(@PathVariable UUID id);

    @PostExchange("/user-tasks/{id}/assign")
    IdDTO assignUserTask(@PathVariable UUID id, @RequestBody AssignUserTaskDTO dto);

    @PostExchange("/incidents/{id}/resolve")
    IdDTO resolveIncident(@PathVariable UUID id, @RequestBody ResolveIncidentDTO dto);

    @PostExchange("/process-instances/{id}/cancel")
    IdDTO cancelProcessInstance(@PathVariable UUID id);

}
