package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.bpmn.model.AdHocSubProcessExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-32: ad-hoc subprocess, phase 1 (internal mode, {@code zeebe:adHoc}).
 * Activated inner elements share the incoming token; the join reuses the generic
 * expected/arrival counters and fires from {@code FlowNavigator.proceedToOutgoing}.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class AdHocSubProcessIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private BpmnService bpmnService;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private VariableRepository variableRepository;

    private UUID start(String fixture) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + fixture));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        return runtimeService.startProcessInstance(dto).getId();
    }

    private List<ActivityEntity> tasks(UUID pi, String elementId, ActivityStatus status) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getBpmnElementId().equals(elementId) && a.getStatus() == status)
            .toList();
    }

    private long openIncidents(UUID pi) {
        return incidentRepository.findAll().stream().filter(i -> i.getCompletedAt() == null)
            .filter(i -> activityRepository.findById(i.getActivityId())
                .map(a -> a.getProcessInstanceId().equals(pi)).orElse(false))
            .count();
    }

    private boolean instanceDone(UUID pi) {
        return queryService.getProcessInstance(pi).getCompletedAt() != null;
    }

    private ProcessVariable stringVar(String name, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(ProcessVariableType.STRING);
        v.setValue(value);
        return v;
    }

    private ProcessVariable booleanVar(String name, boolean value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(ProcessVariableType.BOOLEAN);
        v.setValue(Boolean.toString(value));
        return v;
    }

    private AdHocSubProcessExtensionModel adhocExt(String fixture, String processDefinitionName) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + fixture));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        BpmnProcessDefinitionModel parsed = bpmnService.getProcessDefinitionModelById(model.getId());
        BpmnElementModel adhoc = parsed.getElement("adhoc");
        assertThat(adhoc.getType()).isEqualTo(BpmnElementType.AD_HOC_SUB_PROCESS);
        return adhoc.getExtensions().getAdHocSubProcessExtension();
    }

    @Transactional
    @Test
    void adhocExtension_parsedWithAllFourFields() throws Exception {
        // Criterion 1: zeebe:adHoc + completionCondition/cancelRemainingInstances parse ('=' stripped at parse, like MI).
        AdHocSubProcessExtensionModel ext = adhocExt("test-adhoc-two-tasks.bpmn", "test-adhoc-two-tasks");
        assertThat(ext.getActiveElementsCollection()).isEqualTo("[\"taskA\", \"taskB\"]");
        assertThat(ext.getCompletionCondition()).isNull();
        assertThat(ext.getCancelRemainingInstances()).isNull(); // absent = docs default true, resolved at use
        assertThat(ext.getInnerElementIds()).containsExactlyInAnyOrder("taskA", "taskB");

        AdHocSubProcessExtensionModel cc = adhocExt("test-adhoc-completion-condition.bpmn", "test-adhoc-completion-condition");
        assertThat(cc.getCompletionCondition()).isEqualTo("stopEarly = true");

        AdHocSubProcessExtensionModel nc = adhocExt("test-adhoc-cancel-false.bpmn", "test-adhoc-cancel-false");
        assertThat(nc.getCancelRemainingInstances()).isEqualTo(Boolean.FALSE);

        AdHocSubProcessExtensionModel out = adhocExt("test-adhoc-output.bpmn", "test-adhoc-output");
        assertThat(out.getOutputCollection()).isEqualTo("results");
        assertThat(out.getOutputElement()).isEqualTo("result");
    }

    @Transactional
    @Test
    void twoIndependentTasks_completeOnlyAfterBoth() throws Exception {
        // Criterion 2 (POF main): both activated tasks run; the scope ends only after both.
        UUID pi = start("test-adhoc-two-tasks.bpmn");

        assertThat(tasks(pi, "taskA", ActivityStatus.CREATED)).hasSize(1);
        assertThat(tasks(pi, "taskB", ActivityStatus.CREATED)).hasSize(1);
        assertThat(tasks(pi, "adhoc", ActivityStatus.CREATED)).hasSize(1);
        assertThat(instanceDone(pi)).isFalse();

        runtimeService.completeUserTask(tasks(pi, "taskA", ActivityStatus.CREATED).get(0).getId(), List.of());
        assertThat(instanceDone(pi)).isFalse();
        assertThat(tasks(pi, "adhoc", ActivityStatus.CREATED)).hasSize(1);

        runtimeService.completeUserTask(tasks(pi, "taskB", ActivityStatus.CREATED).get(0).getId(), List.of());
        assertThat(tasks(pi, "adhoc", ActivityStatus.COMPLETED)).hasSize(1);
        assertThat(instanceDone(pi)).isTrue();
    }

    @Transactional
    @Test
    void completionCondition_cancelsRemainingByDefault() throws Exception {
        // Criterion 3: condition true after the first of two -> second cancelled, scope done.
        UUID pi = start("test-adhoc-completion-condition.bpmn");

        runtimeService.completeUserTask(tasks(pi, "taskC1", ActivityStatus.CREATED).get(0).getId(),
            List.of(booleanVar("stopEarly", true)));

        assertThat(tasks(pi, "adhoc", ActivityStatus.COMPLETED)).hasSize(1);
        assertThat(tasks(pi, "taskC2", ActivityStatus.CANCELLED)).hasSize(1);
        assertThat(instanceDone(pi)).isTrue();
    }

    @Transactional
    @Test
    void cancelFalse_scopeFinishesImmediatelyAndSecondLivesOn() throws Exception {
        // Criterion 4: cancelRemainingInstances=false — the scope still finishes as soon as the
        // condition holds (the flag only spares termination); the second element is NOT cancelled.
        UUID pi = start("test-adhoc-cancel-false.bpmn");

        runtimeService.completeUserTask(tasks(pi, "taskD1", ActivityStatus.CREATED).get(0).getId(),
            List.of(booleanVar("stopEarly", true)));

        assertThat(tasks(pi, "adhoc", ActivityStatus.COMPLETED)).hasSize(1);
        assertThat(tasks(pi, "taskD2", ActivityStatus.CREATED)).hasSize(1);
        assertThat(tasks(pi, "taskD2", ActivityStatus.CANCELLED)).isEmpty();
    }

    @Transactional
    @Test
    void invalidId_raisesIncidentAndActivatesNothing() throws Exception {
        // Criterion 5: a value that matches no inner id -> incident, not a silent skip.
        UUID pi = start("test-adhoc-invalid-id.bpmn");

        assertThat(openIncidents(pi)).isGreaterThanOrEqualTo(1);
        assertThat(tasks(pi, "taskE1", ActivityStatus.CREATED)).isEmpty();
        assertThat(tasks(pi, "adhoc", ActivityStatus.ERROR)).hasSize(1);
        assertThat(instanceDone(pi)).isFalse();
    }

    @Transactional
    @Test
    void nestedFork_doesNotCompleteScopePrematurely() throws Exception {
        // Criterion 6: a parallel fork/join inside one activated element runs on fresh tokens —
        // its traffic must not touch the ad-hoc counter; only taskX + taskZ completions count.
        UUID pi = start("test-adhoc-nested-fork.bpmn");

        runtimeService.completeUserTask(tasks(pi, "taskX", ActivityStatus.CREATED).get(0).getId(), List.of());
        assertThat(tasks(pi, "taskA", ActivityStatus.CREATED)).hasSize(1);
        assertThat(tasks(pi, "taskB", ActivityStatus.CREATED)).hasSize(1);
        assertThat(tasks(pi, "adhoc", ActivityStatus.CREATED)).hasSize(1);
        assertThat(instanceDone(pi)).isFalse();

        runtimeService.completeUserTask(tasks(pi, "taskA", ActivityStatus.CREATED).get(0).getId(), List.of());
        runtimeService.completeUserTask(tasks(pi, "taskB", ActivityStatus.CREATED).get(0).getId(), List.of());
        // Fork/join traffic done — the scope must STILL wait (only taskX arrived so far).
        assertThat(tasks(pi, "taskY", ActivityStatus.CREATED)).hasSize(1);
        assertThat(tasks(pi, "adhoc", ActivityStatus.CREATED)).hasSize(1);
        assertThat(instanceDone(pi)).isFalse();

        runtimeService.completeUserTask(tasks(pi, "taskY", ActivityStatus.CREATED).get(0).getId(), List.of());
        assertThat(tasks(pi, "adhoc", ActivityStatus.CREATED)).hasSize(1);
        assertThat(instanceDone(pi)).isFalse();

        runtimeService.completeUserTask(tasks(pi, "taskZ", ActivityStatus.CREATED).get(0).getId(), List.of());
        assertThat(tasks(pi, "adhoc", ActivityStatus.COMPLETED)).hasSize(1);
        assertThat(instanceDone(pi)).isTrue();
    }

    @Transactional
    @Test
    void outputCollection_aggregatedAndVisibleOutside() throws Exception {
        // Criterion 7: each completed inner flow appends outputElement; visible after the scope.
        UUID pi = start("test-adhoc-output.bpmn");

        runtimeService.completeUserTask(tasks(pi, "taskO1", ActivityStatus.CREATED).get(0).getId(),
            List.of(stringVar("result", "r1")));
        runtimeService.completeUserTask(tasks(pi, "taskO2", ActivityStatus.CREATED).get(0).getId(),
            List.of(stringVar("result", "r2")));

        assertThat(instanceDone(pi)).isTrue();
        ProcessVariableEntity results = variableRepository.findByNameAndProcessInstanceId("results", pi).orElseThrow();
        assertThat(results.getType()).isEqualTo(ProcessVariableType.JSON);
        List<String> values = new ObjectMapper().readValue(results.getTextValue(),
            new tools.jackson.core.type.TypeReference<List<String>>() {});
        assertThat(values).containsExactlyInAnyOrder("r1", "r2");
    }

    @Transactional
    @Test
    void emptyCollection_scopeRemainsActiveParked() throws Exception {
        // Docs: "no element is activated and the ad-hoc sub-process remains active".
        UUID pi = start("test-adhoc-empty.bpmn");

        assertThat(tasks(pi, "adhoc", ActivityStatus.CREATED)).hasSize(1);
        assertThat(tasks(pi, "taskF1", ActivityStatus.CREATED)).isEmpty();
        assertThat(openIncidents(pi)).isEqualTo(0);
        assertThat(instanceDone(pi)).isFalse();
    }

    @Transactional
    @Test
    void chainSingleRoot_flowsThroughChainBeforeCompleting() throws Exception {
        // HOLD-regression (CTO, живой прогон): один активированный корень taskX с
        // собственным исходящим на внутренний taskChain («structured sequence» из доки).
        // Прибытие корня обязано засчитаться лишь когда его цепочка дошла до тупика:
        // завершение taskX создаёт taskChain, скоуп жив; завершение taskChain гасит скоуп.
        UUID pi = start("test-adhoc-chain-single-root.bpmn");

        assertThat(tasks(pi, "taskX", ActivityStatus.CREATED)).hasSize(1);
        assertThat(tasks(pi, "adhoc", ActivityStatus.CREATED)).hasSize(1);

        runtimeService.completeUserTask(tasks(pi, "taskX", ActivityStatus.CREATED).get(0).getId(), List.of());
        assertThat(tasks(pi, "taskChain", ActivityStatus.CREATED)).hasSize(1);
        assertThat(tasks(pi, "adhoc", ActivityStatus.CREATED)).hasSize(1);
        assertThat(instanceDone(pi)).isFalse();

        runtimeService.completeUserTask(tasks(pi, "taskChain", ActivityStatus.CREATED).get(0).getId(), List.of());
        assertThat(tasks(pi, "adhoc", ActivityStatus.COMPLETED)).hasSize(1);
        assertThat(instanceDone(pi)).isTrue();
    }
}
