package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.AdHocJobResultDTO;
import com.zorrodev.bpm.contract.dto.CompleteTaskDTO;
import com.zorrodev.bpm.contract.dto.FailServiceTaskDTO;
import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.contract.dto.ThrowErrorDTO;
import com.zorrodev.bpm.contract.dto.ThrowErrorResultDTO;

import java.util.UUID;

public interface ServiceTaskRuntimeOperations {

    IdDTO completeServiceTask(UUID id, CompleteTaskDTO dto);

    /** WO-C8-33: structured ad-hoc scope-job completion (same COMPLETE_SERVICE_TASK grant). */
    IdDTO completeAdHocScopeJob(UUID id, AdHocJobResultDTO dto);

    IdDTO failServiceTask(UUID id, FailServiceTaskDTO dto);

    /** WO-DIFF-5: BPMN error throw with boundary matching (same COMPLETE_SERVICE_TASK grant). */
    ThrowErrorResultDTO throwServiceTaskError(UUID id, ThrowErrorDTO dto);
}
