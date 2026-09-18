package com.zorrodev.bpm.engine.bpmn.xml.extension;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import lombok.Getter;
import lombok.Setter;

/**
 * WO-C8-30: {@code <zeebe:priorityDefinition priority="..."/>} — user tasks only
 * (schema: {@code PriorityDefinition.allowedIn = ['bpmn:UserTask']}). Raw string:
 * static integer or FEEL expression, resolved at activation (NOT the service-task
 * {@code jobPriorityDefinition} — different type, C8-13, do not touch).
 */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class PriorityDefinitionModel {
    @XmlAttribute
    private String priority;
}
