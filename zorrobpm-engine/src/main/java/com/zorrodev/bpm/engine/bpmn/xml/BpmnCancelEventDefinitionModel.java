package com.zorrodev.bpm.engine.bpmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import lombok.Getter;
import lombok.Setter;

/** {@code <bpmn:cancelEventDefinition>} — on a transaction's cancel end event or cancel boundary. */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class BpmnCancelEventDefinitionModel {
    @XmlAttribute
    private String id;
}
