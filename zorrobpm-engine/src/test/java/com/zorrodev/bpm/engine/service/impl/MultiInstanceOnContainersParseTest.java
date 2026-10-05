package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-34 (CTO HOLD 2026-10-05, п.4) — «MI-подпроцесс на токене форка».
 *
 * <p>The interrupting-boundary fix routes an MI host on a fork token down the
 * own-instances branch, which cancels activities carrying the HOST's
 * {@code bpmnElementId}. If a container could be multi-instance, its INNER tasks
 * would carry different ids and survive the interruption — the reviewer asked for
 * either a test proving inner tasks die, or proof that MI on containers is not
 * something this engine supports. This is that proof, at the level where the
 * question is decided: the PARSER.
 *
 * <p>{@code multiInstanceLoopCharacteristics} is attached to exactly two element
 * kinds — {@code serviceTask} and {@code userTask} — so a
 * {@code <subProcess>/<callActivity>} carrying MI silently parses into an ordinary
 * container with NO multi-instance extension. Consequently
 * {@code EventTrigger.isMultiInstanceHost} can never be true for a container, the
 * scope-container branch handles it, and the concern does not exist today. If MI on
 * containers is ever implemented, THIS test fails — which is the point: the day it
 * changes, the fork/MI reasoning has to be revisited.
 */
class MultiInstanceOnContainersParseTest {

    private static BpmnProcessDefinitionModel parse(String file) throws Exception {
        String xml = Files.readString(Paths.get("src/test/files/" + file));
        // same entry point production uses (BpmnParseServiceImpl), like BpmnParseServiceImplTest
        return new BpmnParseServiceImpl().parse(xml);
    }

    @Test
    void multiInstanceOnSubProcess_isNotAttachedToTheParsedElement() throws Exception {
        BpmnProcessDefinitionModel bpmn = parse("test-mi-on-container-parse.bpmn");

        BpmnElementModel sub = bpmn.getElement("miSub");
        assertThat(sub).as("embedded subprocess parsed").isNotNull();
        assertThat(sub.getType()).isEqualTo(BpmnElementType.SUB_PROCESS);
        assertThat(sub.getExtensions() == null ? null : sub.getExtensions().getMultiInstanceExtension())
            .as("MI on a subprocess is NOT parsed into the executable model")
            .isNull();
    }

    @Test
    void multiInstanceOnCallActivity_isNotAttachedToTheParsedElement() throws Exception {
        BpmnProcessDefinitionModel bpmn = parse("test-mi-on-container-parse.bpmn");

        BpmnElementModel call = bpmn.getElement("miCall");
        assertThat(call).as("call activity parsed").isNotNull();
        assertThat(call.getType()).isEqualTo(BpmnElementType.CALL_ACTIVITY);
        assertThat(call.getExtensions() == null ? null : call.getExtensions().getMultiInstanceExtension())
            .as("MI on a call activity is NOT parsed into the executable model")
            .isNull();
    }

    @Test
    void multiInstanceOnUserTaskAndServiceTask_ISAttached() throws Exception {
        // the control pair: the same fixture's task elements DO get the extension,
        // so the two assertions above are about container support, not a parser that
        // dropped MI everywhere
        BpmnProcessDefinitionModel bpmn = parse("test-mi-on-container-parse.bpmn");

        BpmnElementModel userTask = bpmn.getElement("miUser");
        assertThat(userTask.getExtensions()).isNotNull();
        assertThat(userTask.getExtensions().getMultiInstanceExtension())
            .as("MI on a user task still parses (control)")
            .isNotNull();

        BpmnElementModel service = bpmn.getElement("miService");
        assertThat(service.getExtensions()).isNotNull();
        assertThat(service.getExtensions().getMultiInstanceExtension())
            .as("MI on a service task still parses (control)")
            .isNotNull();
    }
}