package com.zorrodev.bpm.engine.bpmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import lombok.Getter;
import lombok.Setter;

/**
 * A definitions-level {@code <bpmn:message id="..." name="..."/>} declaration, optionally carrying a
 * {@code <zeebe:subscription correlationKey="..."/>} under its extension elements.
 */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class BpmnMessageModel {
    @XmlAttribute
    private String id;

    @XmlAttribute
    private String name;

    @XmlElement(name = "extensionElements", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private ExtensionElements extensionElements;
}
