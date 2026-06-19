package com.zorrodev.bpm.engine.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class SignalStartSubscription {
    private UUID id;
    private String processKey;
    private UUID processDefinitionId;
    private String elementId;
    private String signalName;
}
