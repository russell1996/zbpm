package com.zorrodev.bpm.engine.service.query;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.query.ServiceTaskQuery;
import com.zorrodev.bpm.contract.model.ServiceTask;

import java.util.Collection;
import java.util.UUID;

public interface ServiceTaskQueryOperations {

    PagedDataDTO<ServiceTask> findServiceTasks(ServiceTaskQuery query, Collection<UUID> allowedPdIds);

    ServiceTask getServiceTask(UUID id);
}
