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
    @XmlAttribute
    private String bindingType;
    /**
     * WO-C8-17: parsed here; consumed since WO-C8-20 (SyncTaskHandler pins the latest DMN
     * version annotated with this tag, explicit incident when the pair is missing).
     */
    @XmlAttribute
    private String versionTag;
}
