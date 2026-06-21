package com.zorrodev.bpm.engine.dmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import lombok.Getter;
import lombok.Setter;

/** A decision-table {@code <input>}: its {@code <inputExpression>} is a FEEL expression on the variables. */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class DmnInputModel {
    @XmlAttribute
    private String id;
    @XmlAttribute
    private String label;
    @XmlElement(name = "inputExpression", namespace = DmnDefinitionsModel.NS)
    private DmnTextModel inputExpression;
}
