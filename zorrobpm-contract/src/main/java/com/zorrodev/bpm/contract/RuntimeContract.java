package com.zorrodev.bpm.contract;

import com.zorrodev.bpm.contract.dto.CompleteTaskDTO;
import com.zorrodev.bpm.contract.dto.FailServiceTaskDTO;
import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.contract.dto.ResolveIncidentDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

import java.util.UUID;

public interface RuntimeContract {

    @PostExchange("/process-instances")
    IdDTO startProcessInstance(@RequestBody StartProcessInstanceDTO dto);

    @PostExchange("/service-tasks/{id}/complete")
    IdDTO completeServiceTask(@PathVariable UUID id, @RequestBody CompleteTaskDTO dto);

    /** Reports a service-task failure: decrements retries; raises an incident with {@code message} at 0. */
    @PostExchange("/service-tasks/{id}/fail")
    IdDTO failServiceTask(@PathVariable UUID id, @RequestBody FailServiceTaskDTO dto);

    @PostExchange("/user-tasks/{id}/complete")
    IdDTO completeUserTask(@PathVariable UUID id, @RequestBody CompleteTaskDTO dto);

    @PostExchange("/incidents/{id}/resolve")
    IdDTO resolveIncident(@PathVariable UUID id, @RequestBody ResolveIncidentDTO dto);

    @PostExchange("/process-instances/{id}/cancel")
    IdDTO cancelProcessInstance(@PathVariable UUID id);

}
