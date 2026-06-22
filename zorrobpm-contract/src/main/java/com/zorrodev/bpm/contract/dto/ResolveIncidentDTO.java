package com.zorrodev.bpm.contract.dto;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** Body of {@code POST /incidents/{id}/resolve}: optional variables to apply before re-execution
 *  (the incident id is the path). */
@Getter
@Setter
public class ResolveIncidentDTO {
    private List<ProcessVariable> variables;
}
