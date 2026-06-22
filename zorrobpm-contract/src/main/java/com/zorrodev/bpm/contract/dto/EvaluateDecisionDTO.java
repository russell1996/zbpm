package com.zorrodev.bpm.contract.dto;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/** Input variables for an ad-hoc decision evaluation. */
@Getter
@Setter
public class EvaluateDecisionDTO {
    private List<ProcessVariable> variables = new ArrayList<>();
}
