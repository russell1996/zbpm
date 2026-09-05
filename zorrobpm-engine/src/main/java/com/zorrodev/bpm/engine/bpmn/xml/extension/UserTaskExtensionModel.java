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
