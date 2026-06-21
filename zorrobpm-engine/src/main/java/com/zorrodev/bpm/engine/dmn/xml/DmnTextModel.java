package com.zorrodev.bpm.engine.dmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import lombok.Getter;
import lombok.Setter;

/** Any DMN element carrying a {@code <text>} child (input expression, input entry, output entry). */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class DmnTextModel {
    @XmlElement(name = "text", namespace = DmnDefinitionsModel.NS)
    private String text;
}
