package com.zorrodev.bpm.engine.dmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import lombok.Getter;
import lombok.Setter;

/** An {@code <informationRequirement>} of a decision (WO-C8-10: DMN decision requirements graph). */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class InformationRequirementModel {
    @XmlElement(name = "requiredDecision", namespace = DmnDefinitionsModel.NS)
    private DmnHrefModel requiredDecision;
}
