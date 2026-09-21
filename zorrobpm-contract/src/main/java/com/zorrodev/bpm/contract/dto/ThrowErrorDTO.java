package com.zorrodev.bpm.contract.dto;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * WO-DIFF-5: body of {@code POST /service-tasks/{id}/throw-error} (the task id is the path).
 * <ul>
 *   <li>{@code errorCode} — the BPMN error code to throw (matched against error boundary
 *       {@code errorRef} codes; a boundary without a code is a catch-all);</li>
 *   <li>{@code variables} — applied to the instance BEFORE the throw, so the escape branch
 *       observes them (e.g. Raxon S-046 {@code thrownVar}). Without them the thrown context
 *       would have no carrier, and the endpoint would be unusable for its own WO scenario.</li>
 * </ul>
 */
@Getter
@Setter
public class ThrowErrorDTO {
    @NotBlank(message = "errorCode must not be blank")
    private String errorCode;

    @NotNull(message = "variables must not be null (use [] for no variables)")
    private List<ProcessVariable> variables = new ArrayList<>();
}
