package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.AssignUserTaskDTO;
import com.zorrodev.bpm.contract.dto.CompleteTaskDTO;
import com.zorrodev.bpm.contract.dto.IdDTO;

import java.util.UUID;

public interface UserTaskRuntimeOperations {

    IdDTO completeUserTask(UUID id, CompleteTaskDTO dto);

    IdDTO claimUserTask(UUID id);

    IdDTO unclaimUserTask(UUID id);

    IdDTO assignUserTask(UUID id, AssignUserTaskDTO dto);
}
