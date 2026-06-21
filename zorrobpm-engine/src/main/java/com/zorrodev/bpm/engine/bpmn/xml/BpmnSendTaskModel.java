package com.zorrodev.bpm.engine.bpmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code <sendTask>}: two forms. The BPMN-standard {@code messageRef} form throws/correlates a message;
 * the Camunda 8 form carries a {@code <zeebe:taskDefinition>} in {@code extensionElements} and is executed
 * by a job worker (like a service task).
 */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class BpmnSendTaskModel extends BpmnBaseElementModel {
    @XmlAttribute
    private String messageRef;
    @XmlElement(name = "extensionElements", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private ExtensionElements extensionElements;
}
