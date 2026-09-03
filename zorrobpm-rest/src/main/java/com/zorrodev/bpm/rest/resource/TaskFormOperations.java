package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.TaskFormDTO;

import java.util.UUID;

public interface TaskFormOperations {

    TaskFormDTO getUserTaskForm(UUID id);

    TaskFormDTO getStartForm(String key);
}
