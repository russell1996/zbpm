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
    /**
     * WO-C8-30: raw {@code zeebe:priorityDefinition/@priority} — static integer or
     * FEEL expression, resolved at activation (default 50 per docs when absent).
     * Never the service-task {@code jobPriorityDefinition} (different type, C8-13).
     */
    private String priority;
    private String externalReference;
    private String dueDate;
    private String followUpDate;
    /**
     * WO-C8-21: creating task listeners in declaration order (already filtered to
     * {@code eventType="creating"} at parse time). Null when absent — callers treat
     * null/empty as "no phase" and never touch listener state for such elements.
     */
    private List<ListenerModel> creatingListeners;
    /**
     * WO-C8-24: completing task listeners in declaration order (already filtered to
     * {@code eventType="completing"} at parse time). Null when absent — callers treat
     * null/empty as "no phase" and never touch listener state for such elements.
     */
    private List<ListenerModel> completingListeners;
    /**
     * WO-C8-28: assigning task listeners in declaration order (already filtered to
     * {@code eventType="assigning"} at parse time). Null when absent — same contract
     * as the pairs above. Fires on assignment changes (activation with a model
     * assignee, assign-API, claim); deny is deferred (criterion 5), so listeners
     * observe while the assignment parks in {@code pendingAssignee}.
     */
    private List<ListenerModel> assigningListeners;
    /**
     * WO-C8-28: updating task listeners in declaration order (already filtered to
     * {@code eventType="updating"} at parse time). Null when absent — same contract
     * as the pairs above. Fires on complete-with-variables (the only variable-write
     * path); deny is deferred (criterion 5).
     */
    private List<ListenerModel> updatingListeners;
    /**
     * WO-C8-28: canceling task listeners in declaration order (already filtered to
     * {@code eventType="canceling"} at parse time). Null when absent — same contract
     * as the pairs above. Observe-only: Camunda does not support deny for canceling
     * ("it's not possible to deny the cancelation"), so no deny branch exists.
     */
    private List<ListenerModel> cancelingListeners;
}
