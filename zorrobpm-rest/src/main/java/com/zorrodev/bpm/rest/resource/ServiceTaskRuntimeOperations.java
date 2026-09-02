package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.CompleteTaskDTO;
import com.zorrodev.bpm.contract.dto.FailServiceTaskDTO;
import com.zorrodev.bpm.contract.dto.IdDTO;

import java.util.UUID;

public interface ServiceTaskRuntimeOperations {

    IdDTO completeServiceTask(UUID id, CompleteTaskDTO dto);

    IdDTO failServiceTask(UUID id, FailServiceTaskDTO dto);
}
