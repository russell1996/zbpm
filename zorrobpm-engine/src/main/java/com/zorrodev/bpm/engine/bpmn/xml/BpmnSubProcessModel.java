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
public class BpmnSubProcessModel {

    private static final String NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    @XmlAttribute
    private String id;

    @XmlAttribute
    private String name;

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

    @XmlElement(name = "userTask", namespace = NS)
    private List<BpmnUserTaskModel> userTasks;

    @XmlElement(name = "exclusiveGateway", namespace = NS)
    private List<BpmnExclusiveGatewayModel> exclusiveGateways;

    @XmlElement(name = "parallelGateway", namespace = NS)
    private List<BpmnParallelGatewayModel> parallelGateways;
}
