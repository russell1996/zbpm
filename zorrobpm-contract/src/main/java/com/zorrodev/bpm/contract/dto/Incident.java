package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
public class Incident {
    private UUID id;
    private UUID activityId;
    private String message;
    private Instant createdAt;
    private Instant completedAt;
    /** WO-ACL-16: enrichment — name of the process definition the incident belongs to. */
    private String processName;
    /** WO-ACL-16: enrichment — owning process instance id, so the UI can link to the instance. */
    private UUID processInstanceId;
    /** WO-ACL-16: enrichment — BPMN model element id (Activity_1abc), not the activity UUID. */
    private String bpmnElementId;
    /** WO-ACL-16: enrichment — human-readable element name from the model, if present. */
    private String elementName;
}
