package com.zorrodev.bpm.engine.bpmn.xml.extension;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import lombok.Getter;
import lombok.Setter;

/** {@code <zeebe:calledDecision>}: a business rule task's DMN decision id and the result variable. */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class CalledDecisionModel {
    @XmlAttribute
    private String decisionId;
    @XmlAttribute
    private String resultVariable;
}
