package com.zorrodev.bpm.engine.service.query;

import com.zorrodev.bpm.contract.model.ActivityInstance;
import com.zorrodev.bpm.engine.mapper.ActivityInstanceMapper;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ActivityQueryOperationsImpl implements ActivityQueryOperations {

    private final ActivityRepository activityRepository;
    private final ActivityInstanceMapper activityInstanceMapper;

    @Override
    public List<ActivityInstance> getActivities(UUID processInstanceId) {
        return activityRepository.findByProcessInstanceIdOrderByCreatedAtAsc(processInstanceId).stream()
            .map(activityInstanceMapper::toDTO)
            .toList();
    }
}
