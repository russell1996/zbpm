package com.zorrodev.bpm.engine.bpmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class BpmnProcessDefinitionModel {
    @XmlAttribute
    private String id;
    @XmlAttribute
    private String name;
    @XmlAttribute
    private Boolean isExecutable;
    /**
     * WO-ENG-17: {@code camunda:historyTimeToLive} на {@code <bpmn:process>} (сырая строка,
     * nullable — атрибут отсутствует у большинства процессов). Парсинг/валидация — на деплое
     * ({@code ProcessDefinitionServiceImpl}), здесь только passthrough как у versionTag:
     * парсер не владеет HTTP-контрактом 400 (там {@code BpmnParseException}, не {@code ApiException}).
     */
    @XmlAttribute(name = "historyTimeToLive", namespace = "http://camunda.org/schema/1.0/bpmn")
    private String historyTimeToLive;
    /** Process-level BPMN &lt;documentation&gt; (e.g. a link to requirements). */
    @XmlElement(name = "documentation", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private String documentation;
    /** WO-C8-3: process-level {@code <bpmn:extensionElements>} (e.g. {@code zeebe:versionTag}) —
     * the first process-level extension point; flow-element extensions live on the elements. */
    @XmlElement(name = "extensionElements", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private ExtensionElements extensionElements;
    @XmlElement(name = "startEvent", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private List<BpmnStartEventModel> startEvents;
    @XmlElement(name = "endEvent", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private List<BpmnEndEventModel> endEvents;
    @XmlElement(name = "sequenceFlow", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private List<BpmnSequenceFlowModel> flows;
    @XmlElement(name = "serviceTask", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private List<BpmnServiceTaskModel> serviceTasks;
    @XmlElement(name = "sendTask", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private List<BpmnSendTaskModel> sendTasks;
    @XmlElement(name = "receiveTask", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private List<BpmnReceiveTaskModel> receiveTasks;
    @XmlElement(name = "manualTask", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private List<BpmnManualTaskModel> manualTasks;
    // WO-DIFF-9: bare <bpmn:task> (untyped — no concrete task type selected in the modeler).
    @XmlElement(name = "task", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private List<BpmnTaskModel> tasks;
    @XmlElement(name = "scriptTask", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private List<BpmnScriptTaskModel> scriptTasks;
    @XmlElement(name = "businessRuleTask", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private List<BpmnBusinessRuleTaskModel> businessRuleTasks;
    @XmlElement(name = "userTask", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private List<BpmnUserTaskModel> userTasks;
    @XmlElement(name = "exclusiveGateway", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private List<BpmnExclusiveGatewayModel> exclusiveGateways;
    @XmlElement(name = "parallelGateway", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private List<BpmnParallelGatewayModel> parallelGateways;
    @XmlElement(name = "eventBasedGateway", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private List<BpmnEventBasedGatewayModel> eventBasedGateways;
    @XmlElement(name = "inclusiveGateway", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private List<BpmnInclusiveGatewayModel> inclusiveGateways;
    @XmlElement(name = "intermediateCatchEvent", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private List<BpmnIntermediateCatchEventModel> intermediateCatchEvents;
    @XmlElement(name = "intermediateThrowEvent", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private List<BpmnIntermediateThrowEventModel> intermediateThrowEvents;
    @XmlElement(name = "callActivity", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private List<BpmnCallActivityModel> callActivities;
    @XmlElement(name = "subProcess", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private List<BpmnSubProcessModel> subProcesses;
    // a <transaction> is an embedded subprocess with cancel semantics; reuse the same POJO/flattening
    @XmlElement(name = "transaction", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private List<BpmnSubProcessModel> transactions;
    // WO-C8-32: ad-hoc subprocesses share the subprocess POJO shape via inheritance
    // (BpmnAdHocSubProcessModel extends BpmnSubProcessModel) but bind a separate
    // element name, so they never leak into the regular-subprocess path.
    @XmlElement(name = "adHocSubProcess", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private List<BpmnAdHocSubProcessModel> adHocSubProcesses;
    @XmlElement(name = "boundaryEvent", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private List<BpmnBoundaryEventModel> boundaryEvents;
    @XmlElement(name = "association", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private List<BpmnAssociationModel> associations;
}
