package com.zorrodev.bpm.engine.bpmn.xml;

import jakarta.xml.bind.annotation.XmlElement;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BpmnEndEventModel extends BpmnBaseElementModel {
    private static final String NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    @XmlElement(name = "terminateEventDefinition", namespace = NS)
    private Object terminateEventDefinition;

    @XmlElement(name = "errorEventDefinition", namespace = NS)
    private BpmnErrorEventDefinitionModel errorEventDefinition;

    @XmlElement(name = "signalEventDefinition", namespace = NS)
    private BpmnSignalEventDefinitionModel signalEventDefinition;

    @XmlElement(name = "escalationEventDefinition", namespace = NS)
    private BpmnEscalationEventDefinitionModel escalationEventDefinition;

    @XmlElement(name = "messageEventDefinition", namespace = NS)
    private BpmnMessageEventDefinitionModel messageEventDefinition;

    @XmlElement(name = "compensateEventDefinition", namespace = NS)
    private BpmnCompensateEventDefinitionModel compensateEventDefinition;

    @XmlElement(name = "cancelEventDefinition", namespace = NS)
    private BpmnCancelEventDefinitionModel cancelEventDefinition;

    @XmlElement(name = "extensionElements", namespace = NS)
    private ExtensionElements extensionElements;
}
