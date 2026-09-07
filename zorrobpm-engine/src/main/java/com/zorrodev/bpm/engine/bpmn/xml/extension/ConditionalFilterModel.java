package com.zorrodev.bpm.engine.bpmn.xml.extension;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import lombok.Getter;
import lombok.Setter;

/**
 * WO-C8-29: {@code <zeebe:conditionalFilter variableNames="..." variableEvents="..." />}
 * inside {@code <bpmn:conditionalEventDefinition><bpmn:extensionElements>}.
 * Raw model — both attributes are plain strings; splitting/normalizing happens at
 * resolve time (see {@code ConditionalFilter} in {@code bpmn.model}).
 */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class ConditionalFilterModel {
    @XmlAttribute
    private String variableNames;
    @XmlAttribute
    private String variableEvents;
}
