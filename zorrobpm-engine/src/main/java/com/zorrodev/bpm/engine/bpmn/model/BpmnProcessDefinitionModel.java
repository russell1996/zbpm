package com.zorrodev.bpm.engine.bpmn.model;

import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
    /**
     * WO-C8-26: linked-form id of the plain start event ({@code zeebe:formDefinition/@formId}).
     * Wins over the legacy {@code startFormKey} property (docs: the Modeler offers one Form
     * type at a time — linked XOR embedded XOR custom — so coexistence is invalid input).
     */
    @Getter
    @Setter
    private String startFormId;
    /** WO-C8-26: resource binding of the start form ({@code latest} default / {@code deployment}). */
    @Getter
    @Setter
    private String startFormBindingType;
    /**
     * WO-C8-26: parsed into the model ONLY (⛔ граница — место тега в .form-ресурсе не
     * выяснено, WO-C8-27; реализация запрещена).
     */
    @Getter
    @Setter
    private String startFormVersionTag;
    @Getter
    @Setter
    private String versionTag;
    /**
     * WO-ENG-17: сырое значение {@code camunda:historyTimeToLive} (nullable).
     * Строка, не Integer: парсинг/валидация — на деплое рядом с остальными
     * 400-контрактами, модель только несёт значение (паттерн versionTag).
     */
    @Getter
    @Setter
    private String historyTimeToLive;
    @Getter
    @Setter
    private List<com.zorrodev.bpm.engine.bpmn.xml.extension.UserTaskFormModel> userTaskForms;
    @Getter
    @Setter
    private String defaultJobPriority;
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

    /**
     * WO-REL-16: distinct job-worker types this definition dispatches to. Deliberately not filtered
     * by element type — a {@code zeebe:taskDefinition} turns a script task or a send task into a job
     * worker too (see BpmnParseServiceImpl), so the presence of a job name is the criterion, not
     * {@code SERVICE_TASK}. Used to declare the job queues at deployment time instead of lazily on
     * the first message. Insertion-ordered for stable logs/tests.
     */
    public Set<String> getJobTypes() {
        return elements.values().stream()
            .map(BpmnElementModel::getExtensions)
            .filter(Objects::nonNull)
            .map(BpmnElementExtensionModel::getServiceTaskExtension)
            .filter(Objects::nonNull)
            .map(ServiceTaskExtensionModel::getJob)
            .filter(job -> job != null && !job.isBlank())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

}
