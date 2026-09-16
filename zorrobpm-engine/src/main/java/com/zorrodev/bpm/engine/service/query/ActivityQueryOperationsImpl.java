package com.zorrodev.bpm.engine.service.query;

import com.zorrodev.bpm.contract.model.ActivityInstance;
import com.zorrodev.bpm.engine.mapper.ActivityInstanceMapper;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ActivityQueryOperationsImpl implements ActivityQueryOperations {

    private final ActivityRepository activityRepository;
    private final ActivityInstanceMapper activityInstanceMapper;

    /**
     * WO-PERF-7: defensive cap on the legacy unbounded path. The return type stays
     * {@code List} (contract unchanged), but the query itself is bounded: 2000 =
     * 10× {@code QueryPaginationSupport.MAX_PAGE_SIZE} — legit history views fit,
     * an OOM-sized result doesn't. Clients that need more use {@code getActivitiesPaged}.
     */
    public static final int LEGACY_ACTIVITIES_MAX = 2000;

    @Override
    public List<ActivityInstance> getActivities(UUID processInstanceId) {
        var page = PageRequest.of(0, LEGACY_ACTIVITIES_MAX, Sort.by("createdAt").ascending());
        return activityRepository.findByProcessInstanceIdOrderByCreatedAtAsc(processInstanceId, page)
            .getContent().stream()
            .map(activityInstanceMapper::toDTO)
            .toList();
    }

    public com.zorrodev.bpm.contract.dto.PagedDataDTO<ActivityInstance> getActivitiesPaged(UUID processInstanceId, Integer pageIndex, Integer pageSize) {
        var page = new com.zorrodev.bpm.engine.service.query.QueryPaginationSupport()
            .clampedPage(pageIndex, pageSize, org.springframework.data.domain.Sort.by("createdAt").ascending());
        var result = activityRepository.findByProcessInstanceIdOrderByCreatedAtAsc(processInstanceId, page);
        var dto = new com.zorrodev.bpm.contract.dto.PagedDataDTO<ActivityInstance>();
        dto.setTotalElements(result.getTotalElements());
        dto.setPageIndex(result.getNumber());
        dto.setPageSize(result.getSize());
        dto.setData(result.getContent().stream().map(activityInstanceMapper::toDTO).toList());
        return dto;
    }
}
