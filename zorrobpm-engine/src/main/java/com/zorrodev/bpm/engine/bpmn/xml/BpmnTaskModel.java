package com.zorrodev.bpm.engine.bpmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code <task>}: an untyped task — the modeler left the concrete task type
 * unselected (WO-DIFF-9, live prod report: Camunda Web Modeler writes a bare
 * {@code <bpmn:task>} when no Service/User/Manual/... type is chosen).
 * Real Zeebe 8.5 accepts such models at deploy and treats the element as a
 * no-op pass-through (verified live, 2026-09-22) — same as {@code MANUAL_TASK}.
 * No attributes beyond the base element.
 */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class BpmnTaskModel extends BpmnBaseElementModel {
}
