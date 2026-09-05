package com.zorrodev.bpm.engine.dmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import lombok.Getter;
import lombok.Setter;

/**
 * WO-C8-20: {@code <zeebe:versionTag value="...">} inside a DMN {@code <decision>} — the version
 * tag of that decision version (Modeler writes it from the decision's "Version tag" field).
 * First zeebe-namespace read in the {@code dmn/xml} package.
 */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class DmnVersionTagModel {
    @XmlAttribute
    private String value;
}
