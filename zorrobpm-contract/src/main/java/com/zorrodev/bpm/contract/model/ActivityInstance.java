package com.zorrodev.bpm.contract.model;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One activity (execution) of a process instance — the execution-history row that also drives
 * BPMN element highlighting (CREATED/IN_PROGRESS = active, COMPLETED, ERROR = incident).
 */
@Getter
@Setter
public class ActivityInstance {
    private UUID id;
    private UUID processInstanceId;
    private String bpmnElementId;
    private String type;
    private String status;
    private Instant createdAt;
    private Instant completedAt;
}
