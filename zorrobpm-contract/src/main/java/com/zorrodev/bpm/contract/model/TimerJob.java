package com.zorrodev.bpm.contract.model;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** A scheduled timer job (catch / boundary / event-subprocess timer). */
@Getter
@Setter
public class TimerJob {
    private UUID id;
    private UUID activityId;
    private UUID processInstanceId;
    private Instant dueAt;
    private boolean fired;
    private String boundaryElementId;
    private String eventSubprocessId;
    private Instant createdAt;
}
