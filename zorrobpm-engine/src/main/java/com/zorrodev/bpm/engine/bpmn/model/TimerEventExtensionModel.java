package com.zorrodev.bpm.engine.bpmn.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TimerEventExtensionModel {
    private TimerEventType type;
    /** Raw timer value: an ISO-8601 duration (e.g. PT5M) for DURATION, or an ISO-8601 instant for DATE. */
    private String expression;
}
