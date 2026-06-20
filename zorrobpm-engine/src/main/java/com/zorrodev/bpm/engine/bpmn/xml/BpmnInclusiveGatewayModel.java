package com.zorrodev.bpm.engine.bpmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code <inclusiveGateway>}: a diverging split activates every outgoing flow whose condition is true
 * (or the {@code default} flow when none is); a converging join waits for all activated branches.
 */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class BpmnInclusiveGatewayModel extends BpmnBaseElementModel {
    @XmlAttribute(name = "default")
    private String defaultFlow;
}
