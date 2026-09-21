package com.zorrodev.bpm.engine.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * WO-DIFF-5: outcome of
 * {@link com.zorrodev.bpm.engine.service.ActivityService#throwServiceTaskError} —
 * whether a matching error boundary handled the throw ({@code handled}); when not,
 * the id of the "Unhandled BPMN error" incident raised on the throwing activity
 * (null when handled).
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ThrowServiceTaskErrorResult {
    private boolean handled;
    private UUID incidentId;
}
