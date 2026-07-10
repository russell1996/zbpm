package com.zorrodev.bpm.engine.dmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** A {@code <decisionTable>}: inputs, outputs and rules, with a hit policy (default UNIQUE). */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class DmnDecisionTableModel {
    @XmlAttribute
    private String hitPolicy;
    @XmlAttribute
    private String aggregation;
    @XmlElement(name = "input", namespace = DmnDefinitionsModel.NS)
    private List<DmnInputModel> inputs;
    @XmlElement(name = "output", namespace = DmnDefinitionsModel.NS)
    private List<DmnOutputModel> outputs;
    @XmlElement(name = "rule", namespace = DmnDefinitionsModel.NS)
    private List<DmnRuleModel> rules;
}
