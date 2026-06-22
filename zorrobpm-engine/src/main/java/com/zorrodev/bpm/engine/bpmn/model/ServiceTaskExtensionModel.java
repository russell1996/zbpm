package com.zorrodev.bpm.engine.bpmn.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ServiceTaskExtensionModel {
    private String job;
    /** Retry budget from {@code zeebe:taskDefinition retries} (default applied by the engine if null). */
    private Integer retries;
}
