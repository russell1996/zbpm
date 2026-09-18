package com.zorrodev.bpm.engine.service.query;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.model.UserTask;

import java.util.Collection;
import java.util.UUID;

public interface UserTaskQueryOperations {

    PagedDataDTO<UserTask> findUserTasks(UserTaskQuery query, Collection<UUID> allowedPdIds);

    UserTask getUserTask(UUID id);
}
