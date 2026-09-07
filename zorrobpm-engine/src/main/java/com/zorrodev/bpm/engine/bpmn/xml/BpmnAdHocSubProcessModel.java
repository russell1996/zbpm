package com.zorrodev.bpm.engine.bpmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import lombok.Getter;
import lombok.Setter;

/**
 * WO-C8-32: an embedded {@code <bpmn:adHocSubProcess>} — a {@link BpmnSubProcessModel}
 * whose nested flow nodes flatten the same way (inherited lists), except start/end
 * events (forbidden by the Camunda docs — the parser rejects them loudly instead of
 * ignoring them silently). Carries the native BPMN bits ({@code completionCondition},
 * {@code cancelRemainingInstances}) plus {@code zeebe:adHoc}; nested containers
 * ({@code subProcess}/{@code transaction}/{@code adHocSubProcess}) are inherited.
 */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class BpmnAdHocSubProcessModel extends BpmnSubProcessModel {

    private static final String NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    @XmlElement(name = "extensionElements", namespace = NS)
    private ExtensionElements extensionElements;

    @XmlElement(name = "completionCondition", namespace = NS)
    private String completionCondition;

    @XmlAttribute
    private Boolean cancelRemainingInstances;
}
