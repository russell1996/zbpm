package com.zorrodev.bpm.engine.bpmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code <sendTask>}: message-throw in task form. The {@code messageRef} attribute references a
 * {@code <message>} declared at the definitions level (resolved to its name by the parser).
 */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class BpmnSendTaskModel extends BpmnBaseElementModel {
    @XmlAttribute
    private String messageRef;
}
