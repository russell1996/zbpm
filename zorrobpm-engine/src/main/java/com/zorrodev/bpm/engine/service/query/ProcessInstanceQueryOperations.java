package com.zorrodev.bpm.engine.service.query;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.query.ProcessInstanceQuery;
import com.zorrodev.bpm.contract.model.ProcessInstance;

import java.util.Collection;
import java.util.UUID;

public interface ProcessInstanceQueryOperations {

    ProcessInstance getProcessInstance(UUID id);

    PagedDataDTO<ProcessInstance> findProcessInstances(ProcessInstanceQuery query, Collection<UUID> allowedPdIds);
}
