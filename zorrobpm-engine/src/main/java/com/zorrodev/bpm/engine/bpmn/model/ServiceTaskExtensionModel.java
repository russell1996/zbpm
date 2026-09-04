package com.zorrodev.bpm.engine.bpmn.model;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
public class ServiceTaskExtensionModel {
    private String job;
    /** Retry budget from {@code zeebe:taskDefinition retries} (default applied by the engine if null). */
    private Integer retries;
    /** Custom headers from {@code zeebe:taskHeaders} (WO-C8-7) — delivered to the worker via JobDetailModel; null when absent. */
    private Map<String, String> taskHeaders;
    /** Raw priority from {@code zeebe:priorityDefinition} (WO-C8-9) — literal or FEEL, resolved to Integer at enqueue time; null when absent. */
    private String priority;
    /** Start execution listeners from {@code zeebe:executionListeners} (WO-C8-11) — dispatched in declaration order before the real job; null/empty when absent. */
    private List<ListenerModel> startListeners;
}
