package com.zorrodev.bpm.exchange;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;
import java.util.UUID;

@Getter
@Setter
public class JobDetailModel {
    private UUID serviceTaskId;
    private UUID processInstanceId;
    private UUID processDefinitionId;
    private String serviceTaskKey;
    private String job;
    private Map<String, ProcessVariable> variables;
    /** Custom headers from {@code zeebe:taskHeaders} (WO-C8-7) — null when the task declares none. */
    private Map<String, String> taskHeaders;
}
