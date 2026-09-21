package com.zorrodev.bpm.contract.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * WO-DIFF-5: result of {@code POST /service-tasks/{id}/throw-error}.
 * {@code handled=false} mirrors the automatic {@code ErrorEndEvent} path: the throwing
 * activity is marked ERROR and an incident ("Unhandled BPMN error ...") is raised, whose
 * id is returned here (null when handled). A manual throw is never quieter than an
 * automatic one.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ThrowErrorResultDTO {
    private boolean handled;
    private UUID incidentId;
}
