package com.zorrodev.bpm.contract.model;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
public class ProcessInstance {
    private UUID id;
    private UUID parentActivityId;
    private UUID processDefinitionId;
    /** Definition name/key/version of this instance's process (resolved for display). */
    private String processName;
    private String processKey;
    private Integer processVersion;
    private Instant startedAt;
    private Instant completedAt;
    private boolean cancelled;
}
