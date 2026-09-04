package com.zorrodev.bpm.engine.bpmn.model;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
public class ServiceTaskExtensionModel {
    private String job;
    /** Retry budget from {@code zeebe:taskDefinition retries} (default applied by the engine if null). */
    private Integer retries;
    /** Custom headers from {@code zeebe:taskHeaders} (WO-C8-7) — delivered to the worker via JobDetailModel; null when absent. */
    private Map<String, String> taskHeaders;
}
