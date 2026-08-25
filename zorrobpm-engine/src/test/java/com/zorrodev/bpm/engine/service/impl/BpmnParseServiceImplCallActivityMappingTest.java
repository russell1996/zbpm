package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-ENG-11 criterion 6: {@code zeebe:ioMapping} on a CALL_ACTIVITY must be parsed into the element
 * model (before the fix the call-activity toElementModel never invoked attachIoMapping, so explicit
 * Input/Output mappings were silently dropped), and {@code propagateAllParentVariables} must survive
 * XML unmarshalling (previously absent from the JAXB model entirely).
 */
class BpmnParseServiceImplCallActivityMappingTest {

    @Test
    void criterion6_ioMappingOnCallActivity_isParsedIntoElementModel() throws Exception {
        String bpmnStr = Files.readString(Path.of("src/test/files/test-eng11-parse-io-mapping.bpmn"));

        BpmnParseService service = new BpmnParseServiceImpl();
        BpmnProcessDefinitionModel bpmn = service.parse(bpmnStr);

        BpmnElementModel call = bpmn.getElement("call");
        assertThat(call).isNotNull();
        assertThat(call.getExtensions()).isNotNull();
        assertThat(call.getExtensions().getIoMappingExtension()).as(
            "zeebe:ioMapping on a call activity must be parsed (WO-ENG-11: previously always null)"
        ).isNotNull();

        var io = call.getExtensions().getIoMappingExtension();
        assertThat(io.getInputs()).hasSize(1);
        assertThat(io.getInputs().get(0).getTarget()).isEqualTo("mappedVar");
        assertThat(io.getInputs().get(0).getSource()).isEqualTo("=parentVar");
        assertThat(io.getOutputs()).hasSize(1);
        assertThat(io.getOutputs().get(0).getTarget()).isEqualTo("pickedVar");
        assertThat(io.getOutputs().get(0).getSource()).isEqualTo("=childVar");
    }

    @Test
    void propagateAllParentVariables_false_survivesParsing() throws Exception {
        String bpmnStr = Files.readString(Path.of("src/test/files/test-eng11-parse-io-mapping.bpmn"));

        BpmnParseService service = new BpmnParseServiceImpl();
        BpmnProcessDefinitionModel bpmn = service.parse(bpmnStr);

        BpmnElementModel call = bpmn.getElement("call");
        assertThat(call.getExtensions().getCallActivityExtension()).isNotNull();
        assertThat(call.getExtensions().getCallActivityExtension().getPropagateAllParentVariables())
            .as("propagateAllParentVariables=false must reach the execution model, not be dropped by JAXB")
            .isFalse();
    }
}
