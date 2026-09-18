package com.zorrodev.bpm.exchange;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class ServiceTaskCompleted {
    private UUID serviceTaskId;
    /** "SUCCESS" completes the task; "FAILED" reports a worker failure (retries → incident). */
    private String status;
    private String errorMessage;
    private List<ProcessVariable> variables;
}
