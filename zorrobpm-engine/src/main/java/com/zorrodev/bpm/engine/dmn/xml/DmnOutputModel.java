package com.zorrodev.bpm.engine.dmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import lombok.Getter;
import lombok.Setter;

/** A decision-table {@code <output>}: its {@code name} keys the result when there are several outputs. */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class DmnOutputModel {
    @XmlAttribute
    private String id;
    @XmlAttribute
    private String name;
    @XmlAttribute
    private String label;
    @XmlAttribute
    private String typeRef;
}
