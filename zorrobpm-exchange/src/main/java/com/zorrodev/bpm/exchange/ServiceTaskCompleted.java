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
    /**
     * WO-OBS-8: W3C traceparent forwarded from the worker's incoming AMQP headers
     * (nullable — null when the worker got no header, e.g. old producer). The
     * completion listener continues the trace from it; lookup-free by design.
     */
    private String traceParent;
    /**
     * WO-OBS-8: process instance id forwarded alongside (nullable). Lookup-free
     * MDC on the completion path — no DB read per completion just for logging.
     */
    private String processInstanceId;
}
