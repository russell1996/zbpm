package com.zorrodev.bpm.engine.dmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** A {@code <decision>} with its decision table. */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class DmnDecisionModel {
    @XmlAttribute
    private String id;
    @XmlAttribute
    private String name;
    @XmlElement(name = "decisionTable", namespace = DmnDefinitionsModel.NS)
    private DmnDecisionTableModel decisionTable;
    @XmlElement(name = "literalExpression", namespace = DmnDefinitionsModel.NS)
    private DmnTextModel literalExpression;
    @XmlElement(name = "informationRequirement", namespace = DmnDefinitionsModel.NS)
    private List<InformationRequirementModel> informationRequirements;
    @XmlElement(name = "extensionElements", namespace = DmnDefinitionsModel.NS)
    private DmnDecisionExtensionModel extensionElements;
}
