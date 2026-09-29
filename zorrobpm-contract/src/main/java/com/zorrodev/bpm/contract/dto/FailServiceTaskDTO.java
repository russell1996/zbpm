package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * Body of {@code POST /service-tasks/{id}/fail} (the task id is the path).
 * <ul>
 *   <li>{@code message} — the worker's error text (stored in the incident);</li>
 *   <li>{@code retries} — optional remaining retries (Camunda {@code failJob} semantics): when given, the
 *       budget is set to this value ({@code 0} raises the incident immediately); when omitted, the budget is
 *       decremented by one.</li>
 * </ul>
 */
@Getter
@Setter
public class FailServiceTaskDTO {
    private String message;
    private Integer retries;
}
