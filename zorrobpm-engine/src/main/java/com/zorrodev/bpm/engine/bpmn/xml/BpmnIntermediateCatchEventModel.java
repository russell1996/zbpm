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
public class BpmnIntermediateCatchEventModel implements Documented {
    @XmlAttribute
    private String id;

    @XmlAttribute
    private String name;

    @XmlElement(name = "incoming", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private List<String> incoming;

    @XmlElement(name = "outgoing", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private List<String>  outgoing;

    @XmlElement(name = "messageEventDefinition", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private BpmnMessageEventDefinitionModel  messageEventDefinition;

    @XmlElement(name = "timerEventDefinition", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private BpmnTimerEventDefinitionModel  timerEventDefinition;

    @XmlElement(name = "signalEventDefinition", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private BpmnSignalEventDefinitionModel signalEventDefinition;

    @XmlElement(name = "conditionalEventDefinition", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private BpmnConditionalEventDefinitionModel conditionalEventDefinition;

    @XmlElement(name = "linkEventDefinition", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private BpmnLinkEventDefinitionModel linkEventDefinition;

    /** WO-C8-25: {@code zeebe:executionListeners} live here (start phase on events). */
    @XmlElement(name = "extensionElements", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private ExtensionElements extensionElements;

    /** BPMN <documentation> text — surfaced in the UI as element "requirements". */
    @XmlElement(name = "documentation", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private String documentation;
}
