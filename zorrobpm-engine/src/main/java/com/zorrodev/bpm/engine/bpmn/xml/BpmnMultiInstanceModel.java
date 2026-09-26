package com.zorrodev.bpm.engine.bpmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code <bpmn:multiInstanceLoopCharacteristics>}: marks an activity as multi-instance. {@code isSequential}
 * selects sequential vs parallel. The instance count comes either from the BPMN-standard
 * {@code loopCardinality}, or — the Camunda 8 way — from a {@code <zeebe:loopCharacteristics inputCollection=…>}
 * in {@code extensionElements} (the collection's size).
 */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class BpmnMultiInstanceModel {
    @XmlAttribute
    private Boolean isSequential;

    @XmlElement(name = "loopCardinality", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private String loopCardinality;

    @XmlElement(name = "completionCondition", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private String completionCondition;

    @XmlElement(name = "extensionElements", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private ExtensionElements extensionElements;
}
