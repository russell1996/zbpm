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
