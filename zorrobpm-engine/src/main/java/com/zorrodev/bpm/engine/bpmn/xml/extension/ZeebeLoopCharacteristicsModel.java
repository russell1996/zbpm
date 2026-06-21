package com.zorrodev.bpm.engine.bpmn.xml.extension;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code <zeebe:loopCharacteristics>}: the Camunda 8 way to configure a multi-instance activity. The
 * {@code inputCollection} FEEL expression yields the collection to iterate; {@code inputElement} names the
 * per-instance variable; {@code outputCollection}/{@code outputElement} aggregate results.
 */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class ZeebeLoopCharacteristicsModel {
    @XmlAttribute
    private String inputCollection;
    @XmlAttribute
    private String inputElement;
    @XmlAttribute
    private String outputCollection;
    @XmlAttribute
    private String outputElement;
}
