package com.zorrodev.bpm.engine.bpmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import lombok.Getter;
import lombok.Setter;

/** {@code <bpmn:escalationEventDefinition escalationRef="..."/>}. */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class BpmnEscalationEventDefinitionModel {
    @XmlAttribute
    private String id;

    @XmlAttribute
    private String escalationRef;
}
