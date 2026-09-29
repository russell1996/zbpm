package com.zorrodev.bpm.engine.bpmn.xml;

import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BpmnStartEventModel extends BpmnBaseElementModel {
    /** Event-subprocess start events: {@code isInterrupting} (default true) — whether triggering it
     *  cancels the parent scope. Absent/ignored for plain process/subprocess start events. */
    @XmlAttribute
    private Boolean isInterrupting;

    @XmlElement(name = "messageEventDefinition", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private BpmnMessageEventDefinitionModel messageEventDefinition;

    @XmlElement(name = "timerEventDefinition", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private BpmnTimerEventDefinitionModel timerEventDefinition;

    @XmlElement(name = "extensionElements", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private ExtensionElements extensionElements;

    private static final String NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    @XmlElement(name = "errorEventDefinition", namespace = NS)
    private BpmnErrorEventDefinitionModel errorEventDefinition;

    @XmlElement(name = "signalEventDefinition", namespace = NS)
    private BpmnSignalEventDefinitionModel signalEventDefinition;

    @XmlElement(name = "escalationEventDefinition", namespace = NS)
    private BpmnEscalationEventDefinitionModel escalationEventDefinition;

    @XmlElement(name = "conditionalEventDefinition", namespace = NS)
    private BpmnConditionalEventDefinitionModel conditionalEventDefinition;
}
