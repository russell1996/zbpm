package com.zorrodev.bpm.engine.bpmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import lombok.Getter;
import lombok.Setter;

/** {@code <bpmn:compensateEventDefinition activityRef="..." waitForCompletion="..."/>}. */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class BpmnCompensateEventDefinitionModel {
    @XmlAttribute
    private String id;

    @XmlAttribute
    private String activityRef;

    @XmlAttribute
    private Boolean waitForCompletion;
}
