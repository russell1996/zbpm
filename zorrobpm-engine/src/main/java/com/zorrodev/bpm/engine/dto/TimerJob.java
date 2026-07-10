package com.zorrodev.bpm.engine.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
public class TimerJob {
    private UUID id;
    private UUID activityId;
    private Instant dueAt;
    private String boundaryElementId;
    private UUID processInstanceId;
    private String eventSubprocessId;
    private Integer remainingCount;
}
