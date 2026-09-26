package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.engine.bpmn.xml.extension.IoMappingModel;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import lombok.Getter;
import lombok.Setter;

/**
 * WO-DIFF-1: JAXB envelope for a plain subProcess/transaction container's OWN
 * {@code <bpmn:extensionElements>} direct-child block, extracted as raw text
 * (see {@code BpmnParseServiceImpl#subProcessExtensionBlock}) and unmarshalled
 * with the SHARED {@link IoMappingModel} type — the same model the task-level
 * {@code attachIoMapping} path maps, never a simplified copy.
 *
 * <p>A dedicated wrapper (not {@code ExtensionElements} itself) because the
 * block arrives without its outer {@code <bpmn:extensionElements>} element —
 * only the inner {@code zeebe:ioMapping}.
 */
@Getter
@Setter
@XmlRootElement(name = "subProcessIoMapping")
@XmlAccessorType(XmlAccessType.FIELD)
class BpmnSubProcessIoMappingWrapper {

    private static final String ZEEBE = "http://camunda.org/schema/zeebe/1.0";

    @XmlElement(name = "ioMapping", namespace = ZEEBE)
    private IoMappingModel ioMapping;
}
