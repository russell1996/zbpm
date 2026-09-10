package com.zorrodev.bpm.engine.bpmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * An embedded {@code <bpmn:subProcess>}: a container element with its own nested flow nodes and
 * sequence flows. The nested elements are flattened into the process definition; the subprocess
 * element itself carries its incoming/outgoing flows for entry/continuation.
 */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class BpmnSubProcessModel implements Documented {

    private static final String NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    @XmlAttribute
    private String id;

    @XmlAttribute
    private String name;

    /** {@code triggeredByEvent="true"} marks this as an event sub-process (triggered by its start
     *  event's event definition, not by an incoming sequence flow). */
    @XmlAttribute
    private Boolean triggeredByEvent;

    @XmlElement(name = "incoming", namespace = NS)
    private List<String> incoming;

    @XmlElement(name = "outgoing", namespace = NS)
    private List<String> outgoing;

    @XmlElement(name = "startEvent", namespace = NS)
    private List<BpmnStartEventModel> startEvents;

    @XmlElement(name = "endEvent", namespace = NS)
    private List<BpmnEndEventModel> endEvents;

    @XmlElement(name = "sequenceFlow", namespace = NS)
    private List<BpmnSequenceFlowModel> flows;

    @XmlElement(name = "serviceTask", namespace = NS)
    private List<BpmnServiceTaskModel> serviceTasks;

    @XmlElement(name = "scriptTask", namespace = NS)
    private List<BpmnScriptTaskModel> scriptTasks;

    @XmlElement(name = "userTask", namespace = NS)
    private List<BpmnUserTaskModel> userTasks;

    @XmlElement(name = "manualTask", namespace = NS)
    private List<BpmnManualTaskModel> manualTasks;

    @XmlElement(name = "exclusiveGateway", namespace = NS)
    private List<BpmnExclusiveGatewayModel> exclusiveGateways;

    @XmlElement(name = "parallelGateway", namespace = NS)
    private List<BpmnParallelGatewayModel> parallelGateways;

    @XmlElement(name = "intermediateCatchEvent", namespace = NS)
    private List<BpmnIntermediateCatchEventModel> intermediateCatchEvents;

    @XmlElement(name = "intermediateThrowEvent", namespace = NS)
    private List<BpmnIntermediateThrowEventModel> intermediateThrowEvents;

    @XmlElement(name = "businessRuleTask", namespace = NS)
    private List<BpmnBusinessRuleTaskModel> businessRuleTasks;

    @XmlElement(name = "sendTask", namespace = NS)
    private List<BpmnSendTaskModel> sendTasks;

    @XmlElement(name = "receiveTask", namespace = NS)
    private List<BpmnReceiveTaskModel> receiveTasks;

    @XmlElement(name = "inclusiveGateway", namespace = NS)
    private List<BpmnInclusiveGatewayModel> inclusiveGateways;

    @XmlElement(name = "eventBasedGateway", namespace = NS)
    private List<BpmnEventBasedGatewayModel> eventBasedGateways;

    @XmlElement(name = "callActivity", namespace = NS)
    private List<BpmnCallActivityModel> callActivities;

    @XmlElement(name = "subProcess", namespace = NS)
    private List<BpmnSubProcessModel> subProcesses;

    @XmlElement(name = "transaction", namespace = NS)
    private List<BpmnSubProcessModel> transactions;

    // WO-C8-32: ad-hoc nested in (ad-hoc)subprocess — same flattening, own handler.
    @XmlElement(name = "adHocSubProcess", namespace = NS)
    private List<BpmnAdHocSubProcessModel> adHocSubProcesses;

    @XmlElement(name = "boundaryEvent", namespace = NS)
    private List<BpmnBoundaryEventModel> boundaryEvents;

    @XmlElement(name = "association", namespace = NS)
    private List<BpmnAssociationModel> associations;

    /** BPMN <documentation> text — surfaced in the UI as element "requirements". */
    @XmlElement(name = "documentation", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private String documentation;
}
