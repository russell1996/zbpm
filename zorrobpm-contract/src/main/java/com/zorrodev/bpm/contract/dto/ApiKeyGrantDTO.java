package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class ApiKeyGrantDTO {
    private UUID processId;
    private String processKey;
    /** Comma-separated permissions (e.g. "START,FETCH_LOCK") or null when full=true. */
    private String permissions;
    private boolean full;
}
