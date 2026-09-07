package com.zorrodev.bpm.engine.bpmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import lombok.Getter;
import lombok.Setter;

/** {@code <bpmn:conditionalEventDefinition><bpmn:condition>expr</bpmn:condition></...>}. */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class BpmnConditionalEventDefinitionModel {
    @XmlAttribute
    private String id;

    @XmlElement(name = "condition", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private String condition;

    @XmlElement(name = "extensionElements", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private ExtensionElements extensionElements;
}
