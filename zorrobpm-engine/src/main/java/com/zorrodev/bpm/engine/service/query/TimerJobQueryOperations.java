package com.zorrodev.bpm.engine.service.query;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.query.TimerJobQuery;
import com.zorrodev.bpm.contract.model.TimerJob;

import java.util.Collection;
import java.util.UUID;

public interface TimerJobQueryOperations {

    PagedDataDTO<TimerJob> findTimerJobs(TimerJobQuery query, Collection<UUID> allowedPdIds);
}
