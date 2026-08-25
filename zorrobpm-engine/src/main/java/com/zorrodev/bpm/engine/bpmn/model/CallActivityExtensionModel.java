package com.zorrodev.bpm.engine.bpmn.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CallActivityExtensionModel {
    private String processId;
    private String bindingType;
    /** WO-ENG-11: parent→child direction (default true). False = only Input mappings seed the child. */
    private Boolean propagateAllParentVariables;
    /** child→parent direction (default true); explicit Output mappings take precedence (WO-ENG-11). */
    private Boolean propagateAllChildVariables;
}
