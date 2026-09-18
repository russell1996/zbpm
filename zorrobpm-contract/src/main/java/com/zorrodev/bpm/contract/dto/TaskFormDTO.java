package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
public class TaskFormDTO {
    /** "embedded" (linked form in form table), "external" (URL), or "none" (no form). */
    private String type;
    /** Artifact kind — "FORM_JS" or "VARIABLE_SCHEMA". Present for type=embedded. */
    private String kind;
    /** Schema JSON — present only for type=embedded. */
    private String schema;
    /** Prefill data from process instance variables — present only for type=embedded. */
    private Map<String, String> data;
    /** External URL — present only for type=external. */
    private String url;
}
