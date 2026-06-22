package com.zorrodev.bpm.contract.model;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** A message catch / boundary / event-subprocess subscription waiting for correlation. */
@Getter
@Setter
public class MessageSubscription {
    private UUID id;
    private UUID processInstanceId;
    private UUID activityId;
    private String messageName;
    private boolean consumed;
    private String boundaryElementId;
    private String eventSubprocessId;
    private String correlationKey;
    private Instant createdAt;
}
