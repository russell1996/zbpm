package com.zorrodev.bpm.engine.bpmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code <bpmn:multiInstanceLoopCharacteristics>}: marks an activity as multi-instance. {@code isSequential}
 * selects sequential vs parallel; {@code loopCardinality} is the (literal or FEEL) number of instances.
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
}
