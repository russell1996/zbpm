package com.zorrodev.bpm.engine.dmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import lombok.Getter;
import lombok.Setter;

/** An {@code href="#decisionId"} reference to another decision in the same DMN file. */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class DmnHrefModel {
    @XmlAttribute
    private String href;

    /** Strips the leading '#' fragment marker, returning the referenced decision's id. */
    public String getId() {
        return href == null ? null : (href.startsWith("#") ? href.substring(1) : href);
    }
}
