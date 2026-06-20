package com.zorrodev.bpm.engine.dmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** A decision-table {@code <rule>}: one input entry (FEEL unary test) and output entry per column. */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class DmnRuleModel {
    @XmlElement(name = "inputEntry", namespace = DmnDefinitionsModel.NS)
    private List<DmnTextModel> inputEntries;
    @XmlElement(name = "outputEntry", namespace = DmnDefinitionsModel.NS)
    private List<DmnTextModel> outputEntries;
}
