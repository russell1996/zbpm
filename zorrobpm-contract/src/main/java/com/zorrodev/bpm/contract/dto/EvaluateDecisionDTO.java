package com.zorrodev.bpm.contract.dto;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import jakarta.validation.constraints.NotNull;

/** Input variables for an ad-hoc decision evaluation. */
@Getter
@Setter
public class EvaluateDecisionDTO {
    @NotNull(message = "variables must not be null (use [] for no variables)")
    private List<ProcessVariable> variables = new ArrayList<>();
}
