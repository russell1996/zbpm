package com.zorrodev.bpm.engine.bpmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * A {@code <bpmn:boundaryEvent attachedToRef="..."/>}. Only interrupting timer boundaries are
 * executed today; the parser ignores boundaries without a timer definition.
 */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class BpmnBoundaryEventModel {

    private static final String NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    @XmlAttribute
    private String id;

    @XmlAttribute
    private String name;

    @XmlAttribute
    private String attachedToRef;

    @XmlAttribute
    private Boolean cancelActivity;

    @XmlElement(name = "incoming", namespace = NS)
    private List<String> incoming;

    @XmlElement(name = "outgoing", namespace = NS)
    private List<String> outgoing;

    @XmlElement(name = "timerEventDefinition", namespace = NS)
    private BpmnTimerEventDefinitionModel timerEventDefinition;
}
