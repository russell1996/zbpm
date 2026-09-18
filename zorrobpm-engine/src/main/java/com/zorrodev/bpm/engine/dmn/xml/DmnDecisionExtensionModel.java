package com.zorrodev.bpm.engine.dmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import lombok.Getter;
import lombok.Setter;

/** WO-C8-20: {@code <extensionElements>} of a DMN {@code <decision>} (carries zeebe extensions). */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class DmnDecisionExtensionModel {
    @XmlElement(name = "versionTag", namespace = "http://camunda.org/schema/zeebe/1.0")
    private DmnVersionTagModel versionTag;
}
