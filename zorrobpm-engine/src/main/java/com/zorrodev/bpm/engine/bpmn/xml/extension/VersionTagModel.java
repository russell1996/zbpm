package com.zorrodev.bpm.engine.bpmn.xml.extension;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import lombok.Getter;
import lombok.Setter;

/** {@code <zeebe:versionTag value="...">}: tags one process version (WO-C8-3, bindingType="versionTag"). */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class VersionTagModel {
    @XmlAttribute
    private String value;
}
