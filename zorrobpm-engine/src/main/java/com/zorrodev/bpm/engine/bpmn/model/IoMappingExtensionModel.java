package com.zorrodev.bpm.engine.bpmn.model;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Execution model for a task's {@code zeebe:ioMapping}: input mappings applied on activation and output
 * mappings applied on completion. Each mapping evaluates its {@code source} FEEL expression and writes the
 * result to {@code target}.
 */
@Getter
@Setter
public class IoMappingExtensionModel {

    @Getter
    @Setter
    public static class Mapping {
        private String source;
        private String target;
    }

    private List<Mapping> inputs;
    private List<Mapping> outputs;
}
