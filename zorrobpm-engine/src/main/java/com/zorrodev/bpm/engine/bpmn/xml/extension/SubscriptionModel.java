package com.zorrodev.bpm.engine.bpmn.xml.extension;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code <zeebe:subscription correlationKey="= expr"/>} declared under a {@code <bpmn:message>}'s
 * extension elements. The {@code correlationKey} is a FEEL expression evaluated against a subscribing
 * instance's variables to produce the value a published message is matched on.
 */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class SubscriptionModel {
    @XmlAttribute
    private String correlationKey;
}
