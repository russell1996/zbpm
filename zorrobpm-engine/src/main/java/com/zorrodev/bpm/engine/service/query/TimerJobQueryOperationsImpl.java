package com.zorrodev.bpm.engine.service.query;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.query.TimerJobQuery;
import com.zorrodev.bpm.contract.model.TimerJob;
import com.zorrodev.bpm.engine.entity.TimerJobEntity;
import com.zorrodev.bpm.engine.mapper.TimerJobMapper;
import com.zorrodev.bpm.engine.repository.TimerJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TimerJobQueryOperationsImpl implements TimerJobQueryOperations {

    private final TimerJobRepository timerJobRepository;
    private final TimerJobMapper timerJobMapper;
    private final QueryPaginationSupport queryPaginationSupport;

    @Override
    public PagedDataDTO<TimerJob> findTimerJobs(TimerJobQuery query, Collection<UUID> allowedPdIds) {
        List<Specification<TimerJobEntity>> specifications = new LinkedList<>();
        if (allowedPdIds != null && allowedPdIds.isEmpty()) {
            return queryPaginationSupport.emptyPage(query);
        }
        if (allowedPdIds != null) {
            specifications.add(queryPaginationSupport.processInstanceInAllowedDefinitions(allowedPdIds));
        }
        if (query.getId() != null) {
            specifications.add((root, q, cb) -> cb.equal(root.get("id"), query.getId()));
        }
        if (query.getProcessInstanceId() != null) {
            specifications.add(TimerJobRepository.byProcessInstanceId(query.getProcessInstanceId()));
        }
        if (query.getFired() != null) {
            specifications.add(TimerJobRepository.byFired(query.getFired()));
        }
        PageRequest page = queryPaginationSupport.clampedPage(query.getPageIndex(), query.getPageSize(), Sort.by("dueAt").ascending());
        return queryPaginationSupport.toDTO(timerJobRepository.findAll(Specification.allOf(specifications), page), timerJobMapper::toDTO);
    }
}
