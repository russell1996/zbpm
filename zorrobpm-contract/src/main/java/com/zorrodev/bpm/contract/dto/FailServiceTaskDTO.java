package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

/** Body of {@code POST /service-tasks/{id}/fail}: the worker's error message (the task id is the path). */
@Getter
@Setter
public class FailServiceTaskDTO {
    private String message;
}
