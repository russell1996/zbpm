package com.zorrodev.bpm.contract.dto.query;

import lombok.Data;

/**
 * Query parameters for GET /events (ADR-7, WO-EVT-3).
 */
@Data
public class EventQuery {
    private Long since;
    private String type;
    private String processInstanceId;
    private String processDefinitionKey;
    private Integer limit;
}
