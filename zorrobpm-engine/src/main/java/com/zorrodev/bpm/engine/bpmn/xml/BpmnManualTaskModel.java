package com.zorrodev.bpm.engine.bpmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code <manualTask>}: a pass-through flow node (WO-C8-5) — the engine does nothing
 * automatically, the token simply walks through, like a none event. No attributes beyond
 * the base element (Camunda 8 defines no {@code zeebe:} extensions for Manual Task).
 */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class BpmnManualTaskModel extends BpmnBaseElementModel {
}
