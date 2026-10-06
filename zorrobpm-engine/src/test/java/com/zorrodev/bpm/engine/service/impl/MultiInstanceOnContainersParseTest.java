package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.exception.ApiException;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-C8-34 (CTO HOLD 2026-10-05, п.4) + WO-ENG-34 (CTO's scope addition to it) —
 * «MI-подпроцесс на токене форка» / «парсер молча игнорирует
 * {@code multiInstanceLoopCharacteristics} на подпроцессе/call activity».
 *
 * <p>The original statement of this test was a PROOF OF ABSENCE: "MI is not parsed onto a
 * container, therefore {@code EventTrigger.isMultiInstanceHost} can never be true for a container,
 * therefore the fork/MI concern does not exist today". True — and exactly the kind of silence
 * CR-08 is about: the author asked for three instances and got one, with no word.
 *
 * <p>WO-ENG-34 changes the CONTRACT, so this test now asserts the new one: the model is REFUSED AT
 * DEPLOY, with the container ids named, through the production deploy path
 * ({@code ProcessDefinitionService.addProcessDefinition} — what the REST endpoint and the batch
 * deployer call). The parser-level facts are kept below as the MECHANISM of that refusal (why the
 * gate fires at all), so the reasoning that closed the C8-34 question stays visible and stays
 * testable: if MI on containers is ever implemented, these assertions fail on purpose.
 *
 * <p>Per CTO's instruction this is the ONE existing test of the WO that is rewritten; it is
 * rewritten in place (same file, same class name) rather than replaced by a new class, because it
 * is also the C8-34 record.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
@Transactional
class MultiInstanceOnContainersParseTest {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private BpmnParseService bpmnParseService;

    // ─── the new contract: explicit refusal, naming the construct and the containers ───

    @Test
    void multiInstanceOnContainer_isRefusedAtDeployNamingBothContainers() {
        assertThatThrownBy(() -> processDefinitionService.addProcessDefinition(bpmn()))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> {
                ApiException api = (ApiException) ex;
                assertThat(api.getCode())
                    .as("the refusal must name the CONSTRUCT, not a generic parse error")
                    .isEqualTo("UNSUPPORTED_MULTI_INSTANCE_CONTAINER");
                assertThat(api.getParams().get("elementIds")).asList()
                    .containsExactly("miSub", "miCall");
                assertThat(api.getMessage()).contains("multiInstanceLoopCharacteristics");
            });
    }

    @Test
    void multiInstanceOnContainer_isRecordedAsAFindingSoDeployCanRefuseIt() {
        // the mechanism half: parse does not THROW (a model stored by an earlier release must stay
        // loadable — this parser is also the runtime loader), it RECORDS the finding
        BpmnProcessDefinitionModel model = bpmnParseService.parse(bpmn());

        assertThat(model.getUnsupportedConstructs())
            .singleElement()
            .satisfies(finding -> {
                assertThat(finding.code()).isEqualTo("UNSUPPORTED_MULTI_INSTANCE_CONTAINER");
                assertThat(finding.elementIds()).containsExactly("miSub", "miCall");
            });
    }

    // ─── the parser facts that closed the C8-34 question (kept: they are why the gate fires) ───

    @Test
    void multiInstanceOnSubProcess_isNotAttachedToTheParsedElement() throws Exception {
        BpmnProcessDefinitionModel bpmn = bpmnParseService.parse(bpmn());

        BpmnElementModel sub = bpmn.getElement("miSub");
        assertThat(sub).as("embedded subprocess parsed").isNotNull();
        assertThat(sub.getType()).isEqualTo(BpmnElementType.SUB_PROCESS);
        assertThat(sub.getExtensions() == null ? null : sub.getExtensions().getMultiInstanceExtension())
            .as("MI on a subprocess is NOT parsed into the executable model")
            .isNull();
    }

    @Test
    void multiInstanceOnCallActivity_isNotAttachedToTheParsedElement() throws Exception {
        BpmnProcessDefinitionModel bpmn = bpmnParseService.parse(bpmn());

        BpmnElementModel call = bpmn.getElement("miCall");
        assertThat(call).as("call activity parsed").isNotNull();
        assertThat(call.getType()).isEqualTo(BpmnElementType.CALL_ACTIVITY);
        assertThat(call.getExtensions() == null ? null : call.getExtensions().getMultiInstanceExtension())
            .as("MI on a call activity is NOT parsed into the executable model")
            .isNull();
    }

    @Test
    void multiInstanceOnUserTaskAndServiceTask_ISAttached() {
        // the control pair: the same fixture's task elements DO get the extension, so the assertions
        // above are about container support, not a parser that dropped MI everywhere
        BpmnProcessDefinitionModel bpmn = bpmnParseService.parse(bpmn());

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

    @Test
    void multiInstanceOnTheSupportedKinds_stillDeploys() {
        // and the supported cousin of the refused construct goes through the production deploy path
        ProcessDefinition supported = processDefinitionService.addProcessDefinition(
            read("test-eng34-mi-on-tasks.bpmn").replace("mi-on-tasks", "mi-on-tasks-" + UUID.randomUUID()));

        assertThat(supported.getKey()).isNotBlank();
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    private static String bpmn() {
        return read("test-mi-on-container-parse.bpmn");
    }

    private static String read(String filename) {
        try {
            return Files.readString(Paths.get("src/test/files/" + filename));
        } catch (Exception e) {
            throw new RuntimeException("Failed to read " + filename, e);
        }
    }
}