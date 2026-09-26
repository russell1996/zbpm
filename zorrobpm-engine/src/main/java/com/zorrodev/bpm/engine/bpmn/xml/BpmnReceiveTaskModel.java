package com.zorrodev.bpm.engine.bpmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code <receiveTask>}: message-catch in task form (wait state). The {@code messageRef} attribute
 * references a {@code <message>} declared at the definitions level (resolved to its name by the parser).
 */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class BpmnReceiveTaskModel extends BpmnBaseElementModel {
    @XmlAttribute
    private String messageRef;
}
