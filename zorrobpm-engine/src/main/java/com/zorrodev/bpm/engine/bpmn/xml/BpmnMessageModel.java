package com.zorrodev.bpm.engine.bpmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import lombok.Getter;
import lombok.Setter;

/** A definitions-level {@code <bpmn:message id="..." name="..."/>} declaration. */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class BpmnMessageModel {
    @XmlAttribute
    private String id;

    @XmlAttribute
    private String name;
}
