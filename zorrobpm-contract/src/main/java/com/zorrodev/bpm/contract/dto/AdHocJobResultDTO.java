package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * WO-C8-33: structured job result of an ad-hoc sub-process scope (job-worker mode).
 * Mirrors Camunda {@code JobResultAdHocSubProcess}: {@code activateElements[]} plus the
 * two flags (both default false per the raw OpenAPI schema). Deliberately NOT mixed
 * into {@link CompleteTaskDTO} (flat variables) — like Zeebe's discriminated
 * {@code JobResult}, these are different result shapes for different consumers.
 * <p>
 * {@code jobToken} is our generation token (no Zeebe equivalent — our job rows are
 * upserted by activity PK, so a recreated job would otherwise be indistinguishable
 * from the one it replaced; a stale completion must fail explicitly, not silently win).
 */
@Getter
@Setter
public class AdHocJobResultDTO {
    private List<AdHocActivateElementDTO> activateElements = new ArrayList<>();
    private Boolean isCompletionConditionFulfilled;
    private Boolean isCancelRemainingInstances;
    private String jobToken;
}
