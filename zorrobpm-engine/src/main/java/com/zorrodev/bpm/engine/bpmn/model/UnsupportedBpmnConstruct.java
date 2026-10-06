package com.zorrodev.bpm.engine.bpmn.model;

import java.util.List;

/**
 * WO-ENG-34: one group of model defects the engine would otherwise accept with CHANGED SEMANTICS.
 *
 * <p>The parser used to drop these constructs silently (JAXB has no field for them), so a model
 * asking for a loop executed once, a complex gateway vanished, a multi-instance container ran once.
 * Deploy now refuses the model instead — the finding travels from the XML scan on the parsed model
 * to {@code ProcessDefinitionServiceImpl}, which turns it into the same 400 + stable-code answer
 * {@code SERVICE_TASK_MISSING_JOB} already established.
 *
 * @param code         stable, machine-readable reason — the API error code (never localised text)
 * @param elementIds   ids of the offending elements, document order; the {@code <process>} ids for
 *                     the multi-process finding, the flow ids for the unresolvable-ref finding
 * @param description  one sentence naming the construct AND what to do about it, for the log and
 *                     for API clients that show the message
 */
public record UnsupportedBpmnConstruct(String code, List<String> elementIds, String description) {

    public UnsupportedBpmnConstruct {
        elementIds = List.copyOf(elementIds);
    }
}
