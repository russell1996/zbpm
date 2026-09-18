package com.zorrodev.bpm.engine.bpmn.xml.extension;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import lombok.Getter;
import lombok.Setter;

/**
 * WO-C8-32: {@code <zeebe:adHoc activeElementsCollection="..." outputCollection="..."
 * outputElement="..."/>} — inner execution mode only (phase 1); the job-worker mode
 * (WO-C8-33) uses taskDefinition instead. All three attributes are FEEL expressions
 * evaluated at entry (collection) or per inner completion (output pair).
 */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class AdHocModel {
    @XmlAttribute
    private String activeElementsCollection;
    @XmlAttribute
    private String outputCollection;
    @XmlAttribute
    private String outputElement;
}
