package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.exception.ApiException;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-REL-18: deployment must reject service tasks without a job definition,
 * and runtime must not NPE when a jobless service task is reached.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
@Transactional
public class ServiceTaskJobValidationIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    // ─── Criterion 1: deployment rejects serviceTask without job ─────────

    @Test
    void criterion1_deployWithoutJob_rejectedWithElementId() {
        String bpmn = readBpmn("test-service-task-no-job.bpmn");

        assertThatThrownBy(() -> processDefinitionService.addProcessDefinition(bpmn))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> {
                ApiException api = (ApiException) ex;
                assertThat(api.getCode()).isEqualTo("SERVICE_TASK_MISSING_JOB");
                assertThat(api.getMessage()).contains("svc_no_job");
                assertThat(api.getParams()).containsKey("elementIds");
            });
    }

    // ─── Criterion 2: error names concrete element ids ───────────────────

    @Test
    void criterion2_mixedValidAndInvalid_errorNamesOnlyMissing() {
        String bpmn = readBpmn("test-service-task-mixed.bpmn");

        assertThatThrownBy(() -> processDefinitionService.addProcessDefinition(bpmn))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> {
                ApiException api = (ApiException) ex;
                // only svc_bad is missing, svc_ok has zeebe:taskDefinition
                assertThat(api.getMessage()).contains("svc_bad");
                assertThat(api.getMessage()).doesNotContain("svc_ok");
                assertThat(api.getParams().get("elementIds")).asList().containsExactly("svc_bad");
            });
    }

    // ─── Criterion 3: valid BPMN still deploys ──────────────────────────

    @Test
    void criterion3_validBpmnWithJob_deploysNormally() {
        String bpmn = readBpmn("test-service-task-fail.bpmn"); // has zeebe:taskDefinition type="flaky"

        ProcessDefinition def = processDefinitionService.addProcessDefinition(bpmn);

        assertThat(def).isNotNull();
        assertThat(def.getKey()).isEqualTo("test-service-task-fail");
    }

    private static String readBpmn(String filename) {
        try {
            return Files.readString(Paths.get("src/test/files/" + filename));
        } catch (Exception e) {
            throw new RuntimeException("Failed to read " + filename, e);
        }
    }
}
