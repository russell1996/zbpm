package com.zorrodev.bpm.contract.dto;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import jakarta.validation.constraints.NotNull;

/** Body of {@code POST /incidents/{id}/resolve}: optional variables to apply before re-execution
 *  (the incident id is the path). */
@Getter
@Setter
public class ResolveIncidentDTO {
    @NotNull(message = "variables must not be null (use [] for no variables)")
    private List<ProcessVariable> variables;
}
