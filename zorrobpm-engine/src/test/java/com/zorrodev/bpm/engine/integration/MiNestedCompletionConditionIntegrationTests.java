package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.exception.ApiException;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * WO-C8-50: MI во вложенной форме (внутри {@code <bpmn:extensionElements>} хоста).
 *
 * <ul>
 *   <li>Без {@code completionCondition} вложенная форма распознаётся (поведение v44, не ломать) —
 *       на подпроцессе, service task и user task.</li>
 *   <li>С {@code completionCondition} внутри вложенного MI весь MI молча терялся
 *       ({@code isMultiInstance=false}, подпроцесс шёл как одиночный — прод-инцидент
 *       консьюмера v50+): обязан распознаваться, условие — разбираться.</li>
 *   <li>Стандартная форма (прямой потомок) с {@code completionCondition} — без изменений.</li>
 *   <li>MI в XML, не ставший MI-расширением хоста (любая форма, любой тип элемента), —
 *       деплой отклонён 400 с кодом {@code UNSUPPORTED_MULTI_INSTANCE_SHAPE}, а не тихий
 *       одиночный элемент.</li>
 * </ul>
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
@Transactional
class MiNestedCompletionConditionIntegrationTests {

    @Autowired
    private BpmnParseService bpmnParseService;

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    private static final String MI_NO_CC =
        "<bpmn:multiInstanceLoopCharacteristics>"
            + "<bpmn:extensionElements>"
            + "<zeebe:loopCharacteristics inputCollection=\"=items\" inputElement=\"item\" "
            + "outputCollection=\"outs\" outputElement=\"=item\" />"
            + "</bpmn:extensionElements>"
            + "</bpmn:multiInstanceLoopCharacteristics>";

    private static final String MI_WITH_CC =
        "<bpmn:multiInstanceLoopCharacteristics>"
            + "<bpmn:extensionElements>"
            + "<zeebe:loopCharacteristics inputCollection=\"=items\" inputElement=\"item\" "
            + "outputCollection=\"outs\" outputElement=\"=item\" />"
            + "</bpmn:extensionElements>"
            + "<bpmn:completionCondition xsi:type=\"bpmn:tFormalExpression\">"
            + "some o in outs satisfies o = \"STOP\"</bpmn:completionCondition>"
            + "</bpmn:multiInstanceLoopCharacteristics>";

    // ─── (б): вложенный MI с completionCondition ─────────────────────────────

    @Test
    void nestedMiWithCompletionCondition_onSubProcess_bindsMiAndCondition() {
        BpmnProcessDefinitionModel model =
            bpmnParseService.parse(processWith("subProcess", MI_WITH_CC, nestedSubBody()));

        assertThat(miBound(model, "loopHost"))
            .as("nested MI with completionCondition must bind (WO-C8-50 defect: whole MI dropped)")
            .isTrue();
        assertThat(completionConditionOf(model, "loopHost"))
            .as("completionCondition must be parsed like the standard form")
            .contains("STOP");
    }

    @Test
    void nestedMiWithCompletionCondition_onServiceTask_bindsMiAndCondition() {
        BpmnProcessDefinitionModel model =
            bpmnParseService.parse(processWith("serviceTask", MI_WITH_CC, ""));

        assertThat(miBound(model, "loopHost")).as("serviceTask nested MI with CC must bind").isTrue();
        assertThat(completionConditionOf(model, "loopHost")).contains("STOP");
    }

    @Test
    void nestedMiWithCompletionCondition_onUserTask_bindsMiAndCondition() {
        BpmnProcessDefinitionModel model =
            bpmnParseService.parse(processWith("userTask", MI_WITH_CC, ""));

        assertThat(miBound(model, "loopHost")).as("userTask nested MI with CC must bind").isTrue();
        assertThat(completionConditionOf(model, "loopHost")).contains("STOP");
    }

    // ─── (а): вложенная форма без CC — поведение v44, не ломать ───────────────

    @Test
    void nestedMiWithoutCompletionCondition_stillBindsOnAllThreeKinds() {
        for (String host : new String[]{"subProcess", "serviceTask", "userTask"}) {
            BpmnProcessDefinitionModel model =
                bpmnParseService.parse(processWith(host, MI_NO_CC, nestedSubBody()));
            assertThat(miBound(model, "loopHost"))
                .as("%s nested MI without completionCondition must keep binding (v44 behaviour)", host)
                .isTrue();
            assertThat(model.getUnsupportedConstructs())
                .as("%s recognised nested MI must not be refused", host)
                .isEmpty();
        }
    }

    // ─── (в): стандартная форма с CC — без изменений ──────────────────────────

    @Test
    void standardDirectMiWithCompletionCondition_stillBinds() {
        BpmnProcessDefinitionModel model =
            bpmnParseService.parse(processWith("subProcess", "", nestedSubBody(), MI_WITH_CC));

        assertThat(miBound(model, "loopHost")).as("standard direct MI with CC must bind").isTrue();
        assertThat(completionConditionOf(model, "loopHost")).contains("STOP");
    }

    // ─── v51 консьюмера: деплой успешен, MI распознан ─────────────────────────

    @Test
    void consumerV51_deploysWithMiRecognised() throws Exception {
        String bpmn = Files.readString(Path.of("src/test/files/test-c850-consumer-v51.bpmn"));

        BpmnProcessDefinitionModel model = bpmnParseService.parse(bpmn);
        assertThat(miBound(model, "subWorkTaskStage"))
            .as("v51 subWorkTaskStage must be multi-instance")
            .isTrue();
        assertThat(completionConditionOf(model, "subWorkTaskStage")).contains("REJECTED");

        var deployed = processDefinitionService.addProcessDefinition(bpmn);
        assertThat(deployed).as("v51 must deploy").isNotNull();
    }

    // ─── (г): MI в XML без привязки → 400 ─────────────────────────────────────

    @Test
    void nestedMiOnTransaction_deployRefusedWithShapeCode() {
        ApiException refusal = (ApiException) catchThrowable(
            () -> processDefinitionService.addProcessDefinition(
                processWith("transaction", MI_WITH_CC, nestedSubBody())));

        assertThat(refusal)
            .as("nested MI on transaction binds nothing — deploy must refuse, not run once")
            .isNotNull();
        assertThat(refusal.getStatus().value()).isEqualTo(400);
        assertThat(refusal.getCode()).isEqualTo("UNSUPPORTED_MULTI_INSTANCE_SHAPE");
        assertThat(refusal.getParams().get("elementIds")).asList().containsExactly("loopHost");
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static boolean miBound(BpmnProcessDefinitionModel model, String elementId) {
        return model.getElements().stream()
            .filter(e -> elementId.equals(e.getId()))
            .findFirst()
            .map(e -> e.getExtensions() != null && e.getExtensions().getMultiInstanceExtension() != null)
            .orElseThrow(() -> new AssertionError("no element " + elementId + " in the parsed model"));
    }

    private static String completionConditionOf(BpmnProcessDefinitionModel model, String elementId) {
        return model.getElements().stream()
            .filter(e -> elementId.equals(e.getId()))
            .findFirst()
            .map(e -> e.getExtensions().getMultiInstanceExtension().getCompletionCondition())
            .orElseThrow(() -> new AssertionError("no MI extension on " + elementId));
    }

    private static String nestedSubBody() {
        return "<bpmn:startEvent id=\"innerStart\"><bpmn:outgoing>sfInner</bpmn:outgoing></bpmn:startEvent>"
            + "<bpmn:sequenceFlow id=\"sfInner\" sourceRef=\"innerStart\" targetRef=\"innerEnd\" />"
            + "<bpmn:endEvent id=\"innerEnd\"><bpmn:incoming>sfInner</bpmn:incoming></bpmn:endEvent>";
    }

    /**
     * Минимальный процесс с хостом {@code hostKind}, несущим вложенный MI-блок.
     * {@code directMi} — прямой MI-потомок хоста (стандартная форма; обычно пусто).
     */
    private static String processWith(String hostKind, String loopMarker, String hostBody) {
        return processWith(hostKind, loopMarker, hostBody, "");
    }

    private static String processWith(String hostKind, String loopMarker, String hostBody, String directMi) {
        String taskDef = "serviceTask".equals(hostKind)
            ? "<zeebe:taskDefinition type=\"mi-nested-job\" retries=\"3\" />"
            : "";
        String key = "c850-nested-" + hostKind + "-" + UUID.randomUUID().toString().substring(0, 8);
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                              xmlns:zeebe="http://camunda.org/schema/zeebe/1.0"
                              id="D" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="KEY" name="KEY" isExecutable="true">
                <bpmn:startEvent id="startEvent"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
                <bpmn:sequenceFlow id="f1" sourceRef="startEvent" targetRef="loopHost" />
                <bpmn:HOST id="loopHost" name="loopHost">
                  <bpmn:extensionElements>
                    TASKDEF
                    NESTED_MI
                  </bpmn:extensionElements>
                  <bpmn:incoming>f1</bpmn:incoming>
                  <bpmn:outgoing>f2</bpmn:outgoing>
                  LOOP_MARKER
                  HOST_BODY
                </bpmn:HOST>
                <bpmn:sequenceFlow id="f2" sourceRef="loopHost" targetRef="endEvent" />
                <bpmn:endEvent id="endEvent"><bpmn:incoming>f2</bpmn:incoming></bpmn:endEvent>
              </bpmn:process>
            </bpmn:definitions>
            """
            .replace("KEY", key)
            .replace("HOST", hostKind)
            .replace("TASKDEF", taskDef)
            .replace("NESTED_MI", loopMarker.isEmpty() ? "" : loopMarker)
            .replace("LOOP_MARKER", directMi)
            .replace("HOST_BODY", hostBody);
    }
}
