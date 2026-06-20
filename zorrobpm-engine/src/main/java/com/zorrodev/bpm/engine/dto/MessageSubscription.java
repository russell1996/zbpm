package com.zorrodev.bpm.engine.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class MessageSubscription {
    private UUID id;
    private UUID processInstanceId;
    private UUID activityId;
    private String messageName;
    private String boundaryElementId;
    private String eventSubprocessId;
}
