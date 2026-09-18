package com.zorrodev.bpm.engine.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class Token {
    private UUID id;
    private UUID parentId;
    private UUID scopeActivityId;
    /**
     * WO-ENG-1 (durable): Number of unconsumed branches for a gateway-forked token.
     * NULL = linear process (no fork); 0 = all branches consumed; >0 = branches still active.
     */
    private Integer pendingBranches;
}
