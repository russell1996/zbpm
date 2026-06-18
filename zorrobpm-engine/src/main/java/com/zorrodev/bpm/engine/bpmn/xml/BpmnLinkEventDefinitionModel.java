package com.zorrodev.bpm.engine.bpmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import lombok.Getter;
import lombok.Setter;

/** {@code <bpmn:linkEventDefinition name="..."/>}. */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class BpmnLinkEventDefinitionModel {
    @XmlAttribute
    private String id;

    @XmlAttribute
    private String name;
}
