package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "tokens")
public class TokenEntity {
    @Id
    private UUID id;
    private UUID parentId;
    /** Activity id of the enclosing embedded subprocess, or null for the top-level scope. */
    private UUID scopeActivityId;
    /**
     * WO-ENG-1 (durable): Number of unconsumed branches for a gateway-forked token.
     * NULL = linear process (no fork); 0 = all branches consumed; >0 = branches still active.
     * Set by gateway handlers at fork time; decremented by {@code FlowNavigator.finishBranch}.
     */
    private Integer pendingBranches;
}
