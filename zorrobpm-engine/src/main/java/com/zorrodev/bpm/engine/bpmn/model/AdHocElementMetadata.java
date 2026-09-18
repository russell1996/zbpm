package com.zorrodev.bpm.engine.bpmn.model;

import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WO-C8-33: metadata of one directly nested element of an ad-hoc subprocess, harvested
 * at parse time for the {@code adHocSubProcessElements} scope variable (job-worker mode).
 * <p>
 * Field set mirrors the Camunda docs ("special ad-hoc sub-process variables"):
 * {@code elementId}, {@code elementName}, {@code documentation}, {@code properties}.
 * {@code parameters} is the AI-agent feature ({@code fromAi} FEEL function — no AI runner
 * in this project): always null, documented, never populated.
 * {@code properties} is a name→value map ({@code zeebe:properties}); empty when the
 * element declares none.
 */
@Getter
@Setter
public class AdHocElementMetadata {
    private String elementId;
    private String elementName;
    private String documentation;
    private Map<String, String> properties = new LinkedHashMap<>();
    /** AI-agent parameters — always null (out of project scope, WO-C8-33 boundary). */
    private Object parameters;
}
