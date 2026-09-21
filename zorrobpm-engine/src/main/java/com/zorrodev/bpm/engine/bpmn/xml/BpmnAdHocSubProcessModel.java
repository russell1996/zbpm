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
 * <p>
 * WO-DIFF-1: the {@code extensionElements} binding lives HERE (not on the
 * {@code BpmnSubProcessModel} parent) ON PURPOSE — plain
 * {@code <bpmn:subProcess>}/{@code <bpmn:transaction>} carry NO bindable
 * extensionElements in this project (their ioMapping arrives … nowhere: the
 * sub-container has no JAXB field for it and never did). Promoting the binding
 * to the parent would silently rebind ad-hoc's elements onto the parent
 * accessors (JAXB maps both — parent first — and the ad-hoc reader
 * {@code sub.getExtensionElements()} would go blind). The sub-container parse
 * for WO-DIFF-1 reads the raw DOM ({@code getSubProcessExtensionElements},
 * same class, no JAXB change), so this field stays the single binding.
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
