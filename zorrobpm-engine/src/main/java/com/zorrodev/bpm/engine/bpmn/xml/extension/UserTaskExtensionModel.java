package com.zorrodev.bpm.engine.bpmn.xml.extension;

import com.zorrodev.bpm.engine.bpmn.model.ListenerModel;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class UserTaskExtensionModel {
    private String assignee;
    private String candidateUsers;
    private String candidateGroups;
    private String formKey;
    /**
     * WO-C8-22: linked-form id ({@code zeebe:formDefinition/@formId}) — a SEPARATE field,
     * never merged into {@code formKey} (the pre-existing {@code externalReference}→{@code formKey}
     * conflation is left untouched). Null when the model uses formKey/externalReference.
     */
    private String formId;
    /**
     * WO-C8-23: resource binding of the form ({@code latest} default / {@code deployment}).
     * Pinned into the task row at creation; {@code latest}/absent keeps the old path.
     */
    private String bindingType;
    /**
     * WO-C8-23: parsed into the model ONLY (⛔ граница — место тега в .form-ресурсе не
     * выяснено; реализация запрещена, находка — в отчёт).
     */
    private String versionTag;
    private String externalReference;
    private String dueDate;
    private String followUpDate;
    /**
     * WO-C8-21: creating task listeners in declaration order (already filtered to
     * {@code eventType="creating"} at parse time). Null when absent — callers treat
     * null/empty as "no phase" and never touch listener state for such elements.
     */
    private List<ListenerModel> creatingListeners;
}
