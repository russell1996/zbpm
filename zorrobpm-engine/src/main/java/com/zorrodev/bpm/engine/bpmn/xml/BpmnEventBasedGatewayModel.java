package com.zorrodev.bpm.engine.bpmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code <eventBasedGateway>}: arms all of its outgoing catch events and lets them race — the first
 * event that occurs proceeds, the others are cancelled.
 */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class BpmnEventBasedGatewayModel extends BpmnBaseElementModel {
    /** WO-C8-25: {@code zeebe:executionListeners} live here (start phase on gateways). */
    @XmlElement(name = "extensionElements", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private ExtensionElements extensionElements;
}
