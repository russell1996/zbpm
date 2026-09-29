package com.zorrodev.bpm.engine.bpmn.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TimerEventExtensionModel {
    private TimerEventType type;
    /** Raw timer value: an ISO-8601 duration (PT5M) for DURATION, an ISO-8601 instant for DATE, or an
     *  ISO-8601 repeating interval (R[n]/PT…) or cron expression for CYCLE
     *  (see {@code com.zorrodev.bpm.engine.scheduler.TimerExpressions}). */
    private String expression;
}
