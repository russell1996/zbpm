package com.zorrodev.bpm.engine.bpmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import lombok.Getter;
import lombok.Setter;

/** {@code <bpmn:signalEventDefinition signalRef="..."/>}. */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class BpmnSignalEventDefinitionModel {
    @XmlAttribute
    private String id;

    @XmlAttribute
    private String signalRef;
}
