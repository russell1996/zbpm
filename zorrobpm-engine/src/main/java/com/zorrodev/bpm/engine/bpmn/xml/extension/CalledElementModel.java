package com.zorrodev.bpm.engine.bpmn.xml.extension;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class CalledElementModel {
    @XmlAttribute
    private String processId;
    @XmlAttribute
    private String bindingType;
    /** WO-C8-3: tag value for bindingType="versionTag" (matched against the callee's process versionTag). */
    @XmlAttribute
    private String versionTag;
    /** WO-ENG-11: parent→child direction (default true). False = only Input mappings seed the child. */
    @XmlAttribute
    private Boolean propagateAllParentVariables;
    @XmlAttribute
    private Boolean propagateAllChildVariables;
}
