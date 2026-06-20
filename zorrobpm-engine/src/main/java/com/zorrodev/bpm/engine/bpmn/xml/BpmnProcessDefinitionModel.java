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
    @XmlElement(name = "scriptTask", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private List<BpmnScriptTaskModel> scriptTasks;
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
    @XmlElement(name = "boundaryEvent", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private List<BpmnBoundaryEventModel> boundaryEvents;
    @XmlElement(name = "association", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private List<BpmnAssociationModel> associations;
}
