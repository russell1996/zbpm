package com.zorrodev.bpm.engine.bpmn.xml.extension;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class FormDefinitionModel {
    @XmlAttribute
    private String formKey;
    @XmlAttribute
    private String formId;
    @XmlAttribute
    private String externalReference;
    /** WO-C8-23: latest (default) / deployment; versionTag — только парсинг (граница WO). */
    @XmlAttribute
    private String bindingType;
    /**
     * WO-C8-23: parsed into the model ONLY (⛔ граница — где тег живёт в .form-ресурсе,
     * не выяснено, гадать запрещено; наткнёшься на первоисточник — находка в отчёт).
     */
    @XmlAttribute
    private String versionTag;
}
