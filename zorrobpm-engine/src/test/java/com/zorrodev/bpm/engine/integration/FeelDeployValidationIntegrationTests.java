package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.exception.ApiException;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
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
 * WO-C8-56: a model whose FEEL an engine would fail to PARSE at runtime (single-quoted strings,
 * unknown operators) used to deploy fine and die — or worse, misbehave — at the first evaluation.
 * Zeebe 8.5.0 rejects such a model at deploy (verified live in the WO-C8-56 escalation); the
 * engine now does the same, via the FEEL parser it already runs (feel-engine, the
 * {@code FeelEngineApi} singleton).
 *
 * <p>V11: every case goes through the PRODUCTION deploy path
 * ({@code ProcessDefinitionService.addProcessDefinition} — the method the REST endpoint, the
 * batch deployer and the submission-approval flow all funnel into), against the real
 * repositories. Not a unit test of the validator.
 *
 * <p>As with the ENG-34 refusals: the refusal is TOTAL — before the version row, the process
 * registry row and any subscription/job exist, so each rejection case also asserts nothing was
 * written.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
@Transactional
public class FeelDeployValidationIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private ProcessDefinitionRepository processDefinitionRepository;

    @Autowired
    private ProcessRepository processRepository;

    @Test
    void invalidFlowCondition_singleQuotedString_rejectedWith400InvalidFeelExpression() {
        // the EXACT production expression of outgoingLetterPreparationProcess:
        // =returnClass = 'minor'
        String bpmn = readBpmn("test-c856-invalid-feel-condition.bpmn");

        assertThatThrownBy(() -> processDefinitionService.addProcessDefinition(bpmn))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> {
                ApiException api = (ApiException) ex;
                assertThat(api.getStatus()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
                assertThat(api.getCode()).isEqualTo("INVALID_FEEL_EXPRESSION");
                // the offending FLOW, so the modeller goes to the right place
                assertThat(api.getParams().get("elementIds")).asList().containsExactly("flowMinor");
                // the message names the element, the field, and carries the parser's position —
                // 'minor' is the atom the parser choked on
                assertThat(api.getMessage())
                    .contains("flowMinor")
                    .contains("condition")
                    .contains("'minor'");
            });

        // the refusal is total: no version row, no registry row
        assertThat(processDefinitionRepository.findAll())
            .as("no process definition version may exist for a refused model")
            .noneMatch(pd -> pd.getKey().startsWith("testC856InvalidFeelCondition"));
        assertThat(processRepository.findByDefinitionKey("testC856InvalidFeelCondition")).isEmpty();
    }

    @Test
    void invalidUserTaskAssignee_rejectedNamingTheElementAndTheField() {
        String bpmn = readBpmn("test-c856-invalid-feel-assignee.bpmn");

        assertThatThrownBy(() -> processDefinitionService.addProcessDefinition(bpmn))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> {
                ApiException api = (ApiException) ex;
                assertThat(api.getStatus()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
                assertThat(api.getCode()).isEqualTo("INVALID_FEEL_EXPRESSION");
                assertThat(api.getParams().get("elementIds")).asList().containsExactly("task");
                assertThat(api.getMessage())
                    .contains("task")
                    .contains("assignee")
                    .contains("'x'");
            });

        assertThat(processDefinitionRepository.findAll())
            .noneMatch(pd -> pd.getKey().startsWith("testC856InvalidFeelAssignee"));
    }

    @Test
    void invalidConditionAndAssignee_together_rejectedNamingBothElements() {
        // two broken fields in one model — the answer must name BOTH, not just the first:
        // a modeller fixing one and redeploying must not discover the second one at runtime.
        String bpmn = readBpmn("test-c856-invalid-feel-two-broken.bpmn");

        assertThatThrownBy(() -> processDefinitionService.addProcessDefinition(bpmn))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> {
                ApiException api = (ApiException) ex;
                assertThat(api.getCode()).isEqualTo("INVALID_FEEL_EXPRESSION");
                assertThat(api.getParams().get("elementIds"))
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                    .containsExactlyInAnyOrder("flowMinor", "task");
                assertThat(api.getMessage()).contains("flowMinor").contains("task");
            });

        assertThat(processDefinitionRepository.findAll())
            .noneMatch(pd -> pd.getKey().startsWith("testC856TwoBroken"));
    }

    @Test
    void validDoubleQuotedFeel_stillDeploys() {
        // the SUPPORTED cousin of the refused fixture: the same condition with legal double
        // quotes. A rejection feature whose real failure mode is eating working models must not
        // refuse this.
        String key = "c856-ok-" + UUID.randomUUID().toString().substring(0, 8);
        String bpmn = readBpmn("test-c856-valid-feel.bpmn").replace("testC856ValidFeel", key);

        ProcessDefinition deployed = processDefinitionService.addProcessDefinition(bpmn);

        assertThat(deployed).isNotNull();
        assertThat(deployed.getKey()).isEqualTo(key);
        assertThat(processDefinitionRepository.findById(deployed.getId())).isPresent();
    }

    @Test
    void validModelsFromTheRepo_stillDeploy() {
        // the false-positive guard at deploy granularity: models the engine ALREADY runs today —
        // with =-FEEL conditions, MI, ioMapping — must keep deploying. The full 403-fixture scan
        // lives in FeelRepoFixtureValidationTest; this pins the deploy path itself.
        for (String fixture : List.of(
            "test-eng-8-completioncondition.bpmn",
            "test-multi-instance.bpmn",
            "test-conditional-catch.bpmn",
            "test-c845-mi-sub-gateway-lane.bpmn")) {

            String key = "c856-reg-" + UUID.randomUUID().toString().substring(0, 8);
            String bpmn = readBpmn(fixture).replaceAll(
                "(?m)^\\s*<bpmn:process id=\"[^\"]+\"", "<bpmn:process id=\"" + key + "\"");

            ProcessDefinition deployed = processDefinitionService.addProcessDefinition(bpmn);
            assertThat(deployed).as("%s must keep deploying", fixture).isNotNull();
        }
    }

    private static String readBpmn(String filename) {
        try {
            return Files.readString(Paths.get("src/test/files/" + filename));
        } catch (Exception e) {
            throw new RuntimeException("Failed to read " + filename, e);
        }
    }
}
