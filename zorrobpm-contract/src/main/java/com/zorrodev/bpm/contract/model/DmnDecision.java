package com.zorrodev.bpm.contract.model;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.List;

/** A deployed DMN decision table (latest version), with its inputs/outputs/rules for display. */
@Getter
@Setter
public class DmnDecision {
    private String id;
    private String name;
    private int version;
    private Instant createdAt;
    private String hitPolicy;
    private List<DmnInput> inputs;
    private List<DmnOutput> outputs;
    private List<DmnRule> rules;
}
