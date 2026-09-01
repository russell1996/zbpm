package com.zorrodev.bpm.engine.service.query;

import com.zorrodev.bpm.contract.model.ActivityInstance;

import java.util.List;
import java.util.UUID;

public interface ActivityQueryOperations {

    List<ActivityInstance> getActivities(UUID processInstanceId);
}
