package com.zorrodev.bpm.contract.model;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
public class ServiceTask {
    private UUID id;
    private String code;
    private String name;
    private UUID processInstanceId;
    private UUID processDefinitionId;
    private String job;
    /** Activity lifecycle status: CREATED / IN_PROGRESS / COMPLETED / CANCELLED / ERROR. */
    private String status;
    private Instant createdAt;
    private Instant completedAt;
}
