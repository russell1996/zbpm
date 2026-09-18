package com.zorrodev.bpm.engine.bpmn.xml.extension;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import lombok.Getter;
import lombok.Setter;

/** {@code <zeebe:script>}: an inline FEEL {@code expression} and the {@code resultVariable} it is stored in. */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class ZeebeScriptModel {
    @XmlAttribute
    private String expression;
    @XmlAttribute
    private String resultVariable;
}
