package com.zorrodev.bpm.engine.bpmn.xml;

import jakarta.xml.bind.annotation.XmlElement;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code <businessRuleTask>}: evaluates a DMN decision (via {@code zeebe:calledDecision}) or an inline FEEL
 * expression (via {@code zeebe:script}) and stores the result in a variable.
 */
@Getter
@Setter
public class BpmnBusinessRuleTaskModel extends BpmnBaseElementModel {
    @XmlElement(name = "extensionElements", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private ExtensionElements extensionElements;
}
