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
public class BpmnBoundaryEventModel implements Documented {

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

    @XmlElement(name = "errorEventDefinition", namespace = NS)
    private BpmnErrorEventDefinitionModel errorEventDefinition;

    @XmlElement(name = "messageEventDefinition", namespace = NS)
    private BpmnMessageEventDefinitionModel messageEventDefinition;

    @XmlElement(name = "signalEventDefinition", namespace = NS)
    private BpmnSignalEventDefinitionModel signalEventDefinition;

    @XmlElement(name = "escalationEventDefinition", namespace = NS)
    private BpmnEscalationEventDefinitionModel escalationEventDefinition;

    @XmlElement(name = "conditionalEventDefinition", namespace = NS)
    private BpmnConditionalEventDefinitionModel conditionalEventDefinition;

    @XmlElement(name = "compensateEventDefinition", namespace = NS)
    private BpmnCompensateEventDefinitionModel compensateEventDefinition;

    @XmlElement(name = "cancelEventDefinition", namespace = NS)
    private BpmnCancelEventDefinitionModel cancelEventDefinition;

    /** BPMN <documentation> text — surfaced in the UI as element "requirements". */
    @XmlElement(name = "documentation", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private String documentation;
}
