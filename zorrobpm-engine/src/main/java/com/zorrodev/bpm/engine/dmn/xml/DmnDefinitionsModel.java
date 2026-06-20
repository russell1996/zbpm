package com.zorrodev.bpm.engine.dmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** Root {@code <definitions>} of a DMN 1.3 resource (the namespace Camunda 8 Modeler exports). */
@Getter
@Setter
@XmlRootElement(name = "definitions", namespace = DmnDefinitionsModel.NS)
@XmlAccessorType(XmlAccessType.FIELD)
public class DmnDefinitionsModel {
    public static final String NS = "https://www.omg.org/spec/DMN/20191111/MODEL/";

    @XmlElement(name = "decision", namespace = NS)
    private List<DmnDecisionModel> decisions;
}
