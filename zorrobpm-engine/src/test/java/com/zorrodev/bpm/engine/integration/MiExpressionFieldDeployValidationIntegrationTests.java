package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.exception.ApiException;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-C8-55 (F1 campaign, root 3, the MI half): the multi-instance fields
 * {@code inputCollection} and {@code outputElement} are FEEL EXPRESSIONS — live Zeebe 8.5
 * refuses a model that writes them as a static value, at DEPLOY, with the wording
 * «Expected expression but found static value 'items'. An expression must start with '='
 * (e.g. '=items')» (measured on the live oracle,
 * {@code docs/analysis/c8-oracle/feel-iomapping-static-nested.js}: probes
 * {@code mi-inputcollection-noeq}, {@code mi-outputelement-noeq},
 * {@code mi-outputelement-json} — all three are {@code deployError} in
 * {@code docs/analysis/c8-oracle/scenarios/feel-iomapping-static-nested/result-8.5.json}).
 *
 * <p>Two fields of the same marker are NOT expressions and are NOT touched:
 * {@code inputElement} and {@code outputCollection} are VARIABLE NAMES (live 8.5 probe
 * {@code mi-inputelement-plain} completes normally) — the rule must not spread onto them.</p>
 *
 * <p>V11 / G-N: every case goes through the PRODUCTION path — {@code ProcessDefinitionService
 * .addProcessDefinition} (the deploy gate) and {@code RuntimeService.startProcessInstance} →
 * the MI executor → the per-instance jobs. No copy of the deploy-gate logic lives in this
 * test: the expected values are the live-8.5 ones, and the engine's own parser and validator
 * decide.</p>
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
@Transactional
public class MiExpressionFieldDeployValidationIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private ProcessDefinitionRepository processDefinitionRepository;

    @Autowired
    private VariableRepository variableRepository;

    /**
     * The collection without {@code =}: live 8.5 refuses the model at deploy. The engine used to
     * accept it and evaluate {@code items} as FEEL — a THIRD semantics neither Zeebe nor the
     * «static value» rule has (Zeebe knows only the expression form).
     */
    @Test
    void inputCollectionWithoutEquals_isRejectedAtDeploy() {
        String key = uniqueKey("c855MiColl");

        assertThatThrownBy(() -> processDefinitionService.addProcessDefinition(
            miBpmn(key, "items", "=item")))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> {
                ApiException api = (ApiException) ex;
                assertThat(api.getStatus()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
                assertThat(api.getCode()).isEqualTo("INVALID_FEEL_EXPRESSION");
                assertThat(api.getParams().get("elementIds")).asList().containsExactly("miWork");
                assertThat(api.getMessage())
                    .contains("miWork")
                    .contains("multiInstance.inputCollection")
                    .contains("Expected expression but found static value 'items'")
                    .contains("An expression must start with '='");
            });

        assertThat(processDefinitionRepository.findAll())
            .as("a refused model leaves no version row")
            .noneMatch(pd -> pd.getKey().startsWith(key));
    }

    /**
     * The output element without {@code =} — a bare word that happens to be a variable name.
     * Live 8.5 refuses it (probe {@code mi-outputelement-noeq}) even though the text parses as
     * FEEL: the rule is «must start with '='», not «must be valid FEEL».
     */
    @Test
    void outputElementWithoutEquals_isRejectedAtDeploy() {
        String key = uniqueKey("c855MiOut");

        assertThatThrownBy(() -> processDefinitionService.addProcessDefinition(
            miBpmn(key, "=items", "probeId")))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> {
                ApiException api = (ApiException) ex;
                assertThat(api.getStatus()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
                assertThat(api.getCode()).isEqualTo("INVALID_FEEL_EXPRESSION");
                assertThat(api.getParams().get("elementIds")).asList().containsExactly("miWork");
                assertThat(api.getMessage())
                    .contains("miWork")
                    .contains("multiInstance.outputElement")
                    .contains("Expected expression but found static value 'probeId'")
                    .contains("An expression must start with '='");
            });

        assertThat(processDefinitionRepository.findAll())
            .as("a refused model leaves no version row")
            .noneMatch(pd -> pd.getKey().startsWith(key));
    }

    /**
     * The spelling the PRODUCTION corpus uses — the consumer's approval route writes
     * {@code outputElement='{"decision": decision, "comment": comment}'} (no {@code =}; it is
     * the ACTIVE {@code testApprovalRouteProcess} v73 on prod). Live 8.5 refuses that model at
     * deploy too (probe {@code mi-outputelement-json}) — the text is legal FEEL, the missing
     * {@code =} is what makes it a static value there.
     *
     * <p>This case reads the CONSUMER'S OWN BYTES from the tree
     * ({@code test-c855-consumer-noeq-output-element.bpmn} — the original spelling, kept as the
     * evidence; the working fixtures of the C8-40/42/50 tests carry the Zeebe-legal
     * {@code ={...}} form, which behaves identically in this engine).</p>
     */
    @Test
    void theConsumerModelSpelling_isRejectedAtDeploy() throws Exception {
        String key = uniqueKey("c855MiConsumer");
        String bpmn = Files.readString(Paths.get("src/test/files/test-c855-consumer-noeq-output-element.bpmn"));
        assertThat(bpmn)
            .as("the fixture must really carry the spelling without '=' — otherwise it proves nothing")
            .contains("outputElement=\"{&#34;decision&#34;")
            .doesNotContain("outputElement=\"={&#34;decision&#34;");

        assertThatThrownBy(() -> processDefinitionService.addProcessDefinition(bpmn))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> {
                ApiException api = (ApiException) ex;
                assertThat(api.getStatus()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
                assertThat(api.getCode()).isEqualTo("INVALID_FEEL_EXPRESSION");
                assertThat(api.getMessage())
                    .contains("multiInstance.outputElement")
                    .contains("Expected expression but found static value")
                    .contains("An expression must start with '='");
            });

        assertThat(processDefinitionRepository.findAll())
            .as("a refused model leaves no version row")
            .noneMatch(pd -> pd.getKey().startsWith(key));
    }

    /**
     * The regression guard for the refusal: the EXPRESSION form still deploys AND still works —
     * the collection is evaluated as FEEL, one job per element, the scoped element variable is
     * bound, and the output collection aggregates the per-instance results. Without this case a
     * too-broad refusal would pass every «must be refused» test above while breaking every
     * working model.
     */
    @Test
    void miExpressionFieldsWithEquals_stillDeployAndRun() {
        String key = uniqueKey("c855MiOk");
        ProcessDefinition definition = processDefinitionService.addProcessDefinition(
            miBpmn(key, "=items", "=item"));

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(definition.getId());
        dto.setVariables(List.of(jsonVariable("items", "[\"a\",\"b\"]")));
        UUID pi = runtimeService.startProcessInstance(dto).getId();

        List<ActivityEntity> jobs = activityRepository.findAll().stream()
            .filter(a -> pi.equals(a.getProcessInstanceId()))
            .filter(a -> "miWork".equals(a.getBpmnElementId()))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED || a.getStatus() == ActivityStatus.IN_PROGRESS)
            .toList();
        assertThat(jobs).as("one job per collection element").hasSize(2);

        List<ProcessVariableEntity> scoped = variableRepository.findByProcessInstanceId(pi).stream()
            .filter(v -> v.getScopeId() != null && "item".equals(v.getName()))
            .toList();
        assertThat(scoped).extracting(ProcessVariableEntity::getTextValue)
            .containsExactlyInAnyOrder("a", "b");

        jobs.forEach(j -> runtimeService.completeServiceTask(j.getId(), List.of()));

        assertThat(processDefinitionRepository.findById(definition.getId())).isPresent();
    }

    /**
     * {@code inputElement} and {@code outputCollection} are VARIABLE NAMES, not expressions —
     * live 8.5 accepts them without {@code =} (probe {@code mi-inputelement-plain} completes).
     * The refusal must not spread onto them: the model below is written exactly like the
     * production ones (names, no {@code =}) and must deploy.
     */
    @Test
    void inputElementAndOutputCollection_areNamesAndAreNotRefused() {
        String key = uniqueKey("c855MiNames");
        ProcessDefinition definition = processDefinitionService.addProcessDefinition(
            miBpmn(key, "=items", "=item"));

        assertThat(definition).as("inputElement/outputCollection are names — they must not be refused")
            .isNotNull();
        assertThat(processDefinitionRepository.findById(definition.getId())).isPresent();
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private static ProcessVariable jsonVariable(String name, String json) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(ProcessVariableType.JSON);
        v.setValue(json);
        return v;
    }

    private static String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }

    /**
     * A one-MI-service-task model: start → miWork (multi-instance) → end. The MI marker is
     * written in the Camunda 8 spelling ({@code zeebe:loopCharacteristics} inside
     * {@code bpmn:multiInstanceLoopCharacteristics/bpmn:extensionElements}) — the same one the
     * production definitions use.
     */
    private static String miBpmn(String key, String inputCollection, String outputElement) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
            + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
            + "xmlns:zeebe=\"http://camunda.org/schema/zeebe/1.0\" "
            + "id=\"Definitions_" + key + "\" targetNamespace=\"http://bpmn.io/schema/bpmn\">"
            + "<bpmn:process id=\"" + key + "\" name=\"" + key + "\" isExecutable=\"true\">"
            + "<bpmn:startEvent id=\"start\"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>"
            + "<bpmn:serviceTask id=\"miWork\" name=\"miWork\">"
            + "<bpmn:extensionElements>"
            + "<zeebe:taskDefinition type=\"c855Mi\"/>"
            + "</bpmn:extensionElements>"
            + "<bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>"
            + "<bpmn:multiInstanceLoopCharacteristics isSequential=\"false\">"
            + "<bpmn:extensionElements>"
            + "<zeebe:loopCharacteristics inputCollection=\"" + escape(inputCollection) + "\" "
            + "inputElement=\"item\" outputCollection=\"results\" "
            + "outputElement=\"" + escape(outputElement) + "\"/>"
            + "</bpmn:extensionElements>"
            + "</bpmn:multiInstanceLoopCharacteristics>"
            + "</bpmn:serviceTask>"
            + "<bpmn:endEvent id=\"end\"><bpmn:incoming>f2</bpmn:incoming></bpmn:endEvent>"
            + "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"miWork\"/>"
            + "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"miWork\" targetRef=\"end\"/>"
            + "</bpmn:process></bpmn:definitions>";
    }
}
