package com.zorrodev.bpm.engine.bpmn.xml.extension;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import lombok.Getter;
import lombok.Setter;

/**
 * A single {@code <zeebe:input>}/{@code <zeebe:output>} mapping: the {@code source} FEEL expression is
 * evaluated and its result written to the {@code target} variable.
 */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class MappingModel {
    @XmlAttribute
    private String source;
    @XmlAttribute
    private String target;
}
