package com.zorrodev.bpm.engine.service.query;

import com.zorrodev.bpm.contract.model.ActivityInstance;

import java.util.List;
import java.util.UUID;

public interface ActivityQueryOperations {

    List<ActivityInstance> getActivities(UUID processInstanceId);

    com.zorrodev.bpm.contract.dto.PagedDataDTO<ActivityInstance> getActivitiesPaged(UUID processInstanceId, Integer pageIndex, Integer pageSize);
}
