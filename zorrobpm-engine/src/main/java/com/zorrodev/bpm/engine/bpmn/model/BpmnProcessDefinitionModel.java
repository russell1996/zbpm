package com.zorrodev.bpm.engine.bpmn.model;

import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BpmnProcessDefinitionModel {
    @Getter
    @Setter
    private String executionPlatformVersion;
    @Getter
    @Setter
    private String key;
    @Getter
    @Setter
    private String name;
    @Getter
    @Setter
    private String startFormKey;
    private final Map<String, BpmnElementModel> elements = new HashMap<>();
    @Getter
    @Setter
    private BpmnElementModel startEvent;
    private final Map<String, BpmnFlowModel> flows = new HashMap<>();

    /**
     * Adds an element to the process. The process-level start event is set explicitly by the parser
     * (see {@code BpmnParseService}) — it is NOT inferred here, because flattened nested start events
     * (e.g. inside an embedded subprocess) must not be mistaken for the process start.
     */
    public void addElement(BpmnElementModel element) {
        elements.put(element.getId(), element);
    }

    public BpmnElementModel getElement(String bpmnId) {
        return elements.get(bpmnId);
    }

    public void addFlow(BpmnFlowModel flow) {
        flows.put(flow.getFlowId(), flow);
    }

    public BpmnFlowModel getFlow(String flowId) {
        return flows.get(flowId);
    }

    public List<BpmnElementModel> getElements() {
        return elements.values().stream().toList();
    }

    public List<BpmnFlowModel> getFlows() {
        return flows.values().stream().toList();
    }

    public List<BpmnElementModel> getMessageStartEvents() {
        return elements.values().stream()
            .filter(e -> e.getType() == BpmnElementType.MESSAGE_START_EVENT)
            .filter(e -> e.getEventSubProcessId() == null)
            .toList();
    }

    /** Event sub-processes flattened into this definition (containers triggered by an event, not a flow). */
    public List<BpmnElementModel> getEventSubProcesses() {
        return elements.values().stream()
            .filter(e -> e.getType() == BpmnElementType.EVENT_SUB_PROCESS)
            .toList();
    }

    public List<BpmnElementModel> getTimerStartEvents() {
        return elements.values().stream()
            .filter(e -> e.getType() == BpmnElementType.TIMER_START_EVENT)
            .toList();
    }

    public List<BpmnElementModel> getSignalStartEvents() {
        return elements.values().stream()
            .filter(e -> e.getType() == BpmnElementType.SIGNAL_START_EVENT)
            .filter(e -> e.getEventSubProcessId() == null)
            .toList();
    }

}
