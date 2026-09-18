package com.zorrodev.bpm.engine.bpmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import lombok.Getter;
import lombok.Setter;

/**
 * A {@code <bpmn:association>} — used to link a compensation boundary event to its compensation handler
 * activity. Direction is not significant for compensation, so the parser resolves whichever end is the
 * boundary.
 */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class BpmnAssociationModel {
    @XmlAttribute
    private String id;
    @XmlAttribute
    private String sourceRef;
    @XmlAttribute
    private String targetRef;
}
