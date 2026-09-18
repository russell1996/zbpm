package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;

import java.util.UUID;

public interface ProcessInstanceRuntimeOperations {

    IdDTO startProcessInstance(StartProcessInstanceDTO dto);

    IdDTO cancelProcessInstance(UUID id);
}
