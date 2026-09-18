package com.zorrodev.bpm.exchange;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class ServiceTaskCompleteData {
    private UUID serviceTaskId;
    /** "SUCCESS" or "FAILED" — set by the worker/SDK. */
    private String status;
    /** Error text from the worker when {@code status == "FAILED"} (goes into the incident). */
    private String errorMessage;
    private List<ProcessVariable> variables;
}
