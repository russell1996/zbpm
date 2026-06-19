package com.zorrodev.bpm.engine.bpmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
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
}
