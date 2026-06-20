package com.zorrodev.bpm.engine.bpmn.xml.extension;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * {@code <zeebe:ioMapping>}: input mappings applied when the element is activated and output mappings
 * applied when it completes.
 */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class IoMappingModel {
    private static final String ZEEBE = "http://camunda.org/schema/zeebe/1.0";

    @XmlElement(name = "input", namespace = ZEEBE)
    private List<MappingModel> inputs;

    @XmlElement(name = "output", namespace = ZEEBE)
    private List<MappingModel> outputs;
}
