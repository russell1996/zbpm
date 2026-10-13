package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.engine.bpmn.xml.BpmnMultiInstanceModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.ZeebeLoopCharacteristicsModel;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-40: the Camunda 8/Zeebe spelling of the multi-instance marker on a plain
 * subProcess — {@code <bpmn:multiInstanceLoopCharacteristics>} INSIDE the container's
 * own direct {@code <bpmn:extensionElements>} block (consumer's approval-route v2) —
 * must bind to the shared {@link BpmnMultiInstanceModel}, while the standard
 * BPMN 2.0 direct-child spelling (bound by JAXB) and non-MI containers stay untouched.
 */
class BpmnParseSubProcessMultiInstanceTest {

    private static String read(String file) throws Exception {
        return Files.readString(Path.of("src/test/files/" + file));
    }

    @Test
    void camunda8Spelling_inContainerExtensionBlock_isBound() throws Exception {
        String raw = read("test-c840-approval-route-v2.bpmn");

        String block = BpmnParseServiceImpl.subProcessExtensionBlock(
            raw, "subWorkTaskStage", Set.of("multiInstanceLoopCharacteristics"));
        assertThat(block)
            .as("container's own extension block is found (documentation before it is skipped)")
            .isNotNull()
            .contains("<bpmn:multiInstanceLoopCharacteristics")
            .contains("inputCollection");

        BpmnMultiInstanceModel mi = BpmnParseServiceImpl.readSubProcessMultiInstance(raw, "subWorkTaskStage");
        assertThat(mi).as("the MI marker binds to the shared model").isNotNull();
        assertThat(mi.getIsSequential()).as("no isSequential attr → parallel").isNotEqualTo(Boolean.TRUE);
        ZeebeLoopCharacteristicsModel zeebe = mi.getExtensionElements() == null
            ? null : mi.getExtensionElements().getLoopCharacteristics();
        assertThat(zeebe).as("zeebe:loopCharacteristics child is bound").isNotNull();
        assertThat(zeebe.getInputCollection()).isEqualTo("=activities");
        assertThat(zeebe.getInputElement()).isEqualTo("activity");
        assertThat(zeebe.getOutputCollection()).isEqualTo("decisions");
        assertThat(zeebe.getOutputElement())
            .isEqualTo("{\"decision\": decision, \"comment\": comment}");
    }

    @Test
    void standardDirectChildSpelling_readerReturnsNull_jaxbOwnsIt() throws Exception {
        // test-multi-instance.bpmn: the marker is a DIRECT child of the task (standard
        // BPMN 2.0) — bound by JAXB on the model; the extension-block reader must NOT
        // see it (the task has no extensionElements block).
        String raw = read("test-multi-instance.bpmn");
        assertThat(BpmnParseServiceImpl.readSubProcessMultiInstance(raw, "miTask"))
            .as("direct-child spelling never appears in the extension block")
            .isNull();
    }

    @Test
    void nonMiContainer_noMarker_returnsNull() throws Exception {
        String raw = read("test-c840-mi-sub-nested.bpmn");
        BpmnMultiInstanceModel outer = BpmnParseServiceImpl.readSubProcessMultiInstance(raw, "subOuter");
        BpmnMultiInstanceModel inner = BpmnParseServiceImpl.readSubProcessMultiInstance(raw, "subInner");
        assertThat(outer).as("outer MI container: direct-child spelling binds via JAXB, "
            + "the Camunda-8-block reader finds nothing")
            .isNull();
        assertThat(inner).as("plain subProcess: no marker in its own block").isNull();
    }
}
