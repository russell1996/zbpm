package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.AdHocActivateElementDTO;
import com.zorrodev.bpm.contract.dto.AdHocJobResultDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-C8-33: ad-hoc subprocess, phase 2 (job-worker mode, {@code zeebe:taskDefinition}).
 * The worker owns activation decisions via structured job results; the C8-32 join
 * primitives (shared token, selective cancel, output append) are reused, never reimplemented.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class AdHocJobWorkerIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private ServiceTaskRepository serviceTaskRepository;

    @Autowired
    private VariableRepository variableRepository;

    @Autowired
    private IncidentRepository incidentRepository;

    private UUID start() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-adhoc-job-worker.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        return runtimeService.startProcessInstance(dto).getId();
    }

    private ActivityEntity scope(UUID pi) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi) && a.getBpmnElementId().equals("adhoc"))
            .findFirst().orElseThrow();
    }

    private List<ActivityEntity> tasks(UUID pi, String elementId, ActivityStatus status) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getBpmnElementId().equals(elementId) && a.getStatus() == status)
            .toList();
    }

    private String jobToken(UUID pi, UUID scopeId) {
        return variableRepository.findByProcessInstanceId(pi).stream()
            .filter(v -> ("_adhoc_job_" + scopeId).equals(v.getName()))
            .findFirst().map(ProcessVariableEntity::getTextValue).orElse(null);
    }

    private AdHocJobResultDTO result(String jobToken, boolean fulfilled, boolean cancel,
            AdHocActivateElementDTO... activate) {
        AdHocJobResultDTO dto = new AdHocJobResultDTO();
        dto.setJobToken(jobToken);
        dto.setIsCompletionConditionFulfilled(fulfilled);
        dto.setIsCancelRemainingInstances(cancel);
        dto.setActivateElements(List.of(activate));
        return dto;
    }

    private AdHocActivateElementDTO activate(String elementId, ProcessVariable... vars) {
        AdHocActivateElementDTO dto = new AdHocActivateElementDTO();
        dto.setElementId(elementId);
        dto.setVariables(List.of(vars));
        return dto;
    }

    private ProcessVariable stringVar(String name, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(ProcessVariableType.STRING);
        v.setValue(value);
        return v;
    }

    private boolean instanceDone(UUID pi) {
        return queryService.getProcessInstance(pi).getCompletedAt() != null;
    }

    @Transactional
    @Test
    void jobMode_createsScopeJobInsteadOfActivating() throws Exception {
        // Criterion 1: taskDefinition switches to job-worker mode — a job on the scope,
        // no direct activation (taskX/taskY untouched).
        UUID pi = start();

        ActivityEntity adhoc = scope(pi);
        assertThat(adhoc.getStatus()).isIn(ActivityStatus.CREATED, ActivityStatus.IN_PROGRESS);
        assertThat(serviceTaskRepository.findById(adhoc.getId())).isPresent();
        assertThat(tasks(pi, "taskX", ActivityStatus.CREATED)).isEmpty();
        assertThat(tasks(pi, "taskY", ActivityStatus.CREATED)).isEmpty();
        assertThat(jobToken(pi, adhoc.getId())).isNotBlank();
        assertThat(instanceDone(pi)).isFalse();
    }

    @Transactional
    @Test
    @SuppressWarnings("unchecked")
    void elementsVariable_holdsFourDocumentedFieldsAndNullParameters() throws Exception {
        // Criteria 2 + 13: adHocSubProcessElements carries elementId/elementName/
        // documentation/properties per inner element; parameters stays null (fromAi/AI
        // agents are out of project scope — documented, never populated).
        UUID pi = start();
        UUID scopeId = scope(pi).getId();

        ProcessVariableEntity elementsVar = variableRepository.findByProcessInstanceId(pi).stream()
            .filter(v -> "adHocSubProcessElements".equals(v.getName()))
            .findFirst().orElseThrow();
        List<Map<String, Object>> elements = new ObjectMapper().readValue(elementsVar.getTextValue(),
            new tools.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
        assertThat(elements).hasSize(2);

        Map<String, Object> taskX = elements.stream()
            .filter(e -> "taskX".equals(e.get("elementId"))).findFirst().orElseThrow();
        assertThat(taskX.get("elementName")).isEqualTo("Approve request");
        assertThat(taskX.get("documentation")).isEqualTo("Check the request before approval");
        assertThat((Map<String, Object>) taskX.get("properties")).containsEntry("lane", "backoffice");
        assertThat(taskX).containsKey("parameters");
        assertThat(taskX.get("parameters")).isNull();

        Map<String, Object> taskY = elements.stream()
            .filter(e -> "taskY".equals(e.get("elementId"))).findFirst().orElseThrow();
        assertThat(taskY.get("elementName")).isEqualTo("taskY");
        assertThat(taskY.get("parameters")).isNull();
    }

    @Transactional
    @Test
    void workerResult_activatesNamedElements() throws Exception {
        // Criterion 3 (POF main): activateElements=[taskX] runs taskX on the shared token.
        UUID pi = start();
        UUID scopeId = scope(pi).getId();

        runtimeService.completeAdHocScopeJob(scopeId, result(jobToken(pi, scopeId), false, false, activate("taskX")));

        assertThat(tasks(pi, "taskX", ActivityStatus.CREATED)).hasSize(1);
        assertThat(tasks(pi, "taskY", ActivityStatus.CREATED)).isEmpty();
        assertThat(scope(pi).getStatus()).isIn(ActivityStatus.CREATED, ActivityStatus.IN_PROGRESS);
        assertThat(instanceDone(pi)).isFalse();
    }

    @Transactional
    @Test
    void workerResult_bindsElementScopedVariables() throws Exception {
        // Criterion 3 detail: per-element variables land scoped to the created activity
        // (MI-style), not in root.
        UUID pi = start();
        UUID scopeId = scope(pi).getId();

        runtimeService.completeAdHocScopeJob(scopeId,
            result(jobToken(pi, scopeId), false, false, activate("taskX", stringVar("reviewer", "bob"))));

        UUID taskXId = tasks(pi, "taskX", ActivityStatus.CREATED).get(0).getId();
        ProcessVariableEntity scoped = variableRepository.findByProcessInstanceId(pi).stream()
            .filter(v -> "reviewer".equals(v.getName()) && taskXId.equals(v.getScopeId()))
            .findFirst().orElseThrow();
        assertThat(scoped.getTextValue()).isEqualTo("bob");
        assertThat(variableRepository.findByProcessInstanceId(pi).stream()
            .filter(v -> "reviewer".equals(v.getName()) && v.getScopeId() == null)).isEmpty();
    }

    @Transactional
    @Test
    void innerCompletion_recreatesScopeJob() throws Exception {
        // Criterion 4: a settled inner flow offers exactly one current job generation.
        UUID pi = start();
        UUID scopeId = scope(pi).getId();
        String token1 = jobToken(pi, scopeId);

        runtimeService.completeAdHocScopeJob(scopeId, result(token1, false, false, activate("taskX")));
        runtimeService.completeUserTask(tasks(pi, "taskX", ActivityStatus.CREATED).get(0).getId(), List.of());

        String token2 = jobToken(pi, scopeId);
        assertThat(token2).isNotBlank().isNotEqualTo(token1);
        assertThat(serviceTaskRepository.findById(scopeId)).isPresent();
        assertThat(scope(pi).getStatus()).isIn(ActivityStatus.CREATED, ActivityStatus.IN_PROGRESS);
        assertThat(instanceDone(pi)).isFalse();
    }

    @Transactional
    @Test
    void fulfilledResult_finishesScopeAndCancelsRest() throws Exception {
        // Criterion 5: worker-owned finish; its cancel flag decides (schema default false
        // is overridden here with explicit true).
        UUID pi = start();
        UUID scopeId = scope(pi).getId();

        runtimeService.completeAdHocScopeJob(scopeId,
            result(jobToken(pi, scopeId), false, false, activate("taskX"), activate("taskY")));
        runtimeService.completeUserTask(tasks(pi, "taskX", ActivityStatus.CREATED).get(0).getId(), List.of());
        runtimeService.completeAdHocScopeJob(scopeId, result(jobToken(pi, scopeId), true, true));

        assertThat(tasks(pi, "adhoc", ActivityStatus.COMPLETED)).hasSize(1);
        assertThat(tasks(pi, "taskY", ActivityStatus.CANCELLED)).hasSize(1);
        assertThat(instanceDone(pi)).isTrue();
    }

    @Transactional
    @Test
    void fulfilledResult_withoutCancelLeavesRestAlive() throws Exception {
        // Criterion 5, second half: cancel=false spares the remaining elements.
        UUID pi = start();
        UUID scopeId = scope(pi).getId();

        runtimeService.completeAdHocScopeJob(scopeId, result(jobToken(pi, scopeId), false, false, activate("taskY")));
        runtimeService.completeAdHocScopeJob(scopeId, result(jobToken(pi, scopeId), true, false));

        assertThat(tasks(pi, "adhoc", ActivityStatus.COMPLETED)).hasSize(1);
        assertThat(tasks(pi, "taskY", ActivityStatus.CREATED)).hasSize(1);
        assertThat(tasks(pi, "taskY", ActivityStatus.CANCELLED)).isEmpty();
    }

    @Transactional
    @Test
    void fulfilledWithActivation_isRejected() throws Exception {
        // Raw schema: "cannot fulfill both the completion condition and activate new
        // elements at the same time".
        UUID pi = start();
        UUID scopeId = scope(pi).getId();

        assertThatThrownBy(() -> runtimeService.completeAdHocScopeJob(scopeId,
            result(jobToken(pi, scopeId), true, false, activate("taskX"))))
            .isInstanceOf(com.zorrodev.bpm.contract.exception.ApiException.class)
            .matches(e -> ((com.zorrodev.bpm.contract.exception.ApiException) e).getStatus() == HttpStatus.BAD_REQUEST
                && "AD_HOC_RESULT_CONTRADICTION".equals(((com.zorrodev.bpm.contract.exception.ApiException) e).getCode()));
    }

    @Transactional
    @Test
    void staleJobCompletion_failsExplicitly() throws Exception {
        // Criterion 6: completing a superseded generation is 409, not a silent success;
        // unknown ids stay 404 on the existing path.
        UUID pi = start();
        UUID scopeId = scope(pi).getId();
        String token1 = jobToken(pi, scopeId);

        runtimeService.completeAdHocScopeJob(scopeId, result(token1, false, false, activate("taskX")));
        assertThat(jobToken(pi, scopeId)).isNotEqualTo(token1);

        assertThatThrownBy(() -> runtimeService.completeAdHocScopeJob(scopeId,
            result(token1, false, false, activate("taskY"))))
            .isInstanceOf(com.zorrodev.bpm.contract.exception.ApiException.class)
            .matches(e -> ((com.zorrodev.bpm.contract.exception.ApiException) e).getStatus() == HttpStatus.CONFLICT
                && "AD_HOC_JOB_STALE".equals(((com.zorrodev.bpm.contract.exception.ApiException) e).getCode()));
        // The stale attempt activated nothing and consumed nothing current.
        assertThat(tasks(pi, "taskY", ActivityStatus.CREATED)).isEmpty();

        assertThatThrownBy(() -> runtimeService.completeAdHocScopeJob(UUID.randomUUID(),
            result(token1, false, false)))
            .isInstanceOf(NoSuchElementException.class);
    }

    @Transactional
    @Test
    void invalidWorkerElementId_raisesIncident() throws Exception {
        // Docs mirror of the internal mode: unknown ids incident, nothing runs, job consumed.
        UUID pi = start();
        UUID scopeId = scope(pi).getId();

        runtimeService.completeAdHocScopeJob(scopeId, result(jobToken(pi, scopeId), false, false, activate("ghost")));

        assertThat(tasks(pi, "ghost", ActivityStatus.CREATED)).isEmpty();
        assertThat(scope(pi).getStatus()).isEqualTo(ActivityStatus.ERROR);
        long open = incidentRepository.findAll().stream().filter(i -> i.getCompletedAt() == null)
            .filter(i -> activityRepository.findById(i.getActivityId())
                .map(a -> a.getProcessInstanceId().equals(pi)).orElse(false))
            .count();
        assertThat(open).isGreaterThanOrEqualTo(1);
    }

    @Transactional
    @Test
    void completeAdhocOnNonAdhocActivity_isRejected() throws Exception {
        // Guard branch: the structured endpoint only serves ad-hoc scopes.
        UUID pi = start();
        UUID scopeId = scope(pi).getId();

        runtimeService.completeAdHocScopeJob(scopeId,
            result(jobToken(pi, scopeId), false, false, activate("taskX")));
        UUID taskXId = tasks(pi, "taskX", ActivityStatus.CREATED).get(0).getId();

        assertThatThrownBy(() -> runtimeService.completeAdHocScopeJob(taskXId,
            result("whatever", false, false)))
            .isInstanceOf(com.zorrodev.bpm.contract.exception.ApiException.class)
            .matches(e -> ((com.zorrodev.bpm.contract.exception.ApiException) e).getStatus() == HttpStatus.BAD_REQUEST
                && "AD_HOC_SCOPE_EXPECTED".equals(((com.zorrodev.bpm.contract.exception.ApiException) e).getCode()));
    }
}
