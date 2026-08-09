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
    private Instant createdAt;
    private String boundaryElementId;
    private UUID processInstanceId;
    private String eventSubprocessId;
    private Integer remainingCount;
    /** WO-REL-14: the original timeCycle expression, used to re-arm at the real interval. */
    private String expression;
}
