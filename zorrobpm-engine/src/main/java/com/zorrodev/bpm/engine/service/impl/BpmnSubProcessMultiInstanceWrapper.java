package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.engine.bpmn.xml.BpmnMultiInstanceModel;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import lombok.Getter;
import lombok.Setter;

/**
 * WO-C8-40: JAXB envelope for the container-OWN {@code <bpmn:multiInstanceLoopCharacteristics>}
 * as written by the Camunda 8/Zeebe dialect — INSIDE the container's direct
 * {@code <bpmn:extensionElements>} block (the consumer's approval-route v2 writes it exactly
 * this way; the standard BPMN 2.0 direct-child spelling is bound by the JAXB field on
 * {@code BpmnSubProcessModel} and never reaches this wrapper).
 *
 * <p>Same envelope pattern as {@link BpmnSubProcessIoMappingWrapper}: the extracted element
 * text arrives without a root, so it is wrapped under a dedicated root that binds the
 * SHARED {@link BpmnMultiInstanceModel} type — the very same model the task-level
 * {@code attachMultiInstance} path maps, never a simplified copy.
 */
@Getter
@Setter
@XmlRootElement(name = "subProcessMultiInstance")
@XmlAccessorType(XmlAccessType.FIELD)
class BpmnSubProcessMultiInstanceWrapper {

    private static final String BPMN = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    @XmlElement(name = "multiInstanceLoopCharacteristics", namespace = BPMN)
    private BpmnMultiInstanceModel multiInstance;
}
