package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.query.ServiceTaskQuery;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.contract.model.ServiceTask;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
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

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-DIFF-1 (Raxon diff-findings №1 ioMapping, №3 embedded-subprocess scope):
 * reproduction of both findings on current master, through the engine's own
 * runtime services (same driver role as the Raxon harness worker: deploy via
 * API, start with input vars, complete service tasks with output vars, read
 * back the final variable set).
 *
 * <p>The "harness-visible" set is the ROOT scope
 * ({@code findByProcessInstanceIdAndScopeIdIsNull}) — exactly what
 * {@code GET /variables?processInstanceId=...} returns after WO-ENG-14
 * (root-only default), i.e. what the Raxon harness compares against the
 * Zeebe oracle.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class Diff1IoMappingScopeReproTest {

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
    private VariableRepository variableRepository;

    private ProcessVariable var(String name, ProcessVariableType type, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(type);
        v.setValue(value);
        return v;
    }

    /** Root-scope names — the harness-visible final set (WO-ENG-14 read API semantic). */
    private Map<String, String> rootVars(UUID processInstanceId) {
        return variableRepository.findByProcessInstanceIdAndScopeIdIsNull(processInstanceId).stream()
            .collect(Collectors.toMap(ProcessVariableEntity::getName, ProcessVariableEntity::getTextValue,
                (a, b) -> b));
    }

    private UUID openServiceTaskId(UUID processInstanceId, String jobType) {
        // NOTE: findServiceTasks ignores ServiceTaskQuery.jobType (no spec for it),
        // so filter open tasks by job client-side.
        ServiceTaskQuery q = new ServiceTaskQuery();
        q.setProcessInstanceId(processInstanceId);
        q.setCompleted(false);
        PagedDataDTO<ServiceTask> tasks = queryService.findServiceTasks(q, null);
        List<ServiceTask> match = tasks.getData().stream()
            .filter(t -> jobType.equals(t.getJob()))
            .toList();
        assertThat(match).as("open task " + jobType).hasSize(1);
        return match.get(0).getId();
    }

    private ActivityEntity activityOf(UUID processInstanceId, String bpmnElementId) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .filter(a -> a.getBpmnElementId().equals(bpmnElementId))
            .findFirst().orElseThrow(() -> new IllegalStateException("no activity " + bpmnElementId));
    }

    @Transactional
    @Test
    void finding1_s006_inputMappingIsAppliedThenWiped_outputMappingReachesRoot() throws Exception {
        // Raxon S-006: input =orderId -> orderIdCopy, output =workResult -> finalResult,
        // start {orderId: "123"}, worker returns {workResult: done}.
        // Oracle final: {orderId, orderIdCopy, workResult, finalResult}.
        // Reported Zorro: {orderId, workResult, finalResult} — no orderIdCopy.
        String bpmn = Files.readString(Paths.get("src/test/files/test-diff1-s006.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of(var("orderId", ProcessVariableType.STRING, "123")));
        UUID processInstanceId = runtimeService.startProcessInstance(dto).getId();

        // Mid-flight: the input mapping DID apply — orderIdCopy exists at task scope.
        // The engine is fine; the variable just never survives task completion.
        ActivityEntity work = activityOf(processInstanceId, "work");
        List<ProcessVariableEntity> scoped = variableRepository
            .findByProcessInstanceIdAndScopeId(processInstanceId, work.getId());
        assertThat(scoped.stream().map(ProcessVariableEntity::getName))
            .as("input-mapped orderIdCopy at task scope mid-flight")
            .contains("orderIdCopy");
        assertThat(scoped.stream().filter(e -> e.getName().equals("orderIdCopy"))
            .map(ProcessVariableEntity::getTextValue))
            .containsExactly("123");

        runtimeService.completeServiceTask(openServiceTaskId(processInstanceId, "diff1-s006-work"),
            List.of(var("workResult", ProcessVariableType.STRING, "done")));

        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNotNull();

        // REPRODUCED №1: harness-visible set lacks orderIdCopy (matches the reported Zorro set exactly).
        assertThat(rootVars(processInstanceId).keySet())
            .containsExactlyInAnyOrder("orderId", "workResult", "finalResult");
        // ...while the output mapping provably reached root.
        assertThat(rootVars(processInstanceId)).containsEntry("finalResult", "done");
        // And the input-local is wiped from the DB entirely (not just hidden by the read API).
        assertThat(variableRepository.findByNameAndProcessInstanceId("orderIdCopy", processInstanceId))
            .as("input-local orderIdCopy deleted on completion").isEmpty();
    }

    @Transactional
    @Test
    void finding3_s027_subprocessIoMappingParsedSeededAndPromoted() throws Exception {
        // Raxon S-027: sub input =parentVar -> subLocal, sub output =subLocal -> subOut;
        // inner input =subLocal -> innerSeen, inner output =noSuchVar -> nullOut;
        // after input =subLocal -> lateRead. Start {parentVar: parent},
        // inner worker returns {innerOut: done}, after worker returns {workResult: done}.
        // Oracle final (8): {parentVar, subLocal, innerSeen, innerOut, nullOut, subOut, lateRead, workResult}.
        // Reported Zorro (4): {parentVar, innerOut, nullOut, workResult}.
        // Fixed Zorro (5): reported 4 + subOut promoted from the sub scope to root.
        // Residual DIFFER vs the oracle (documented, WO-DIFF-1 п.3): subLocal/innerSeen/lateRead
        // stay out of root — the oracle unions every VARIABLE record of the trace (including dead
        // task-local scopes), while the harness reads the live root slice and the standing
        // task-local-deletion decision (WO-ENG-14 characterization) is preserved.
        String bpmn = Files.readString(Paths.get("src/test/files/test-diff1-s027.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        // WO-DIFF-1 п.1, parse: the subProcess element carries its ioMapping extension
        // (dropped entirely before the fix; contrast WO-ENG-11 callActivity, which attaches it).
        BpmnProcessDefinitionModel parsed = bpmnService.getProcessDefinitionModelById(model.getId());
        BpmnElementModel sub = parsed.getElement("sub");
        assertThat(sub).as("parsed sub element").isNotNull();
        assertThat(sub.getExtensions().getIoMappingExtension())
            .as("sub ioMapping parsed").isNotNull();
        assertThat(sub.getExtensions().getIoMappingExtension().getInputs())
            .as("sub input mappings").hasSize(1);
        assertThat(sub.getExtensions().getIoMappingExtension().getOutputs())
            .as("sub output mappings").hasSize(1);
        // Control: the nested service task's own ioMapping was parsed all along.
        assertThat(parsed.getElement("inner").getExtensions().getIoMappingExtension())
            .as("inner ioMapping parsed").isNotNull();

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of(var("parentVar", ProcessVariableType.STRING, "parent")));
        UUID processInstanceId = runtimeService.startProcessInstance(dto).getId();

        // WO-DIFF-1 п.1, runtime: the sub input mapping is seeded into the sub scope at entry.
        ActivityEntity subActivity = activityOf(processInstanceId, "sub");
        List<ProcessVariableEntity> subScoped = variableRepository
            .findByProcessInstanceIdAndScopeId(processInstanceId, subActivity.getId());
        assertThat(subScoped.stream().map(ProcessVariableEntity::getName))
            .as("seeded subLocal at sub scope")
            .contains("subLocal");
        assertThat(subScoped.stream().filter(e -> e.getName().equals("subLocal"))
            .map(ProcessVariableEntity::getTextValue))
            .containsExactly("parent");

        // WO-DIFF-1 п.2, visibility down: inner input =subLocal -> innerSeen resolves
        // against the enclosing sub scope mid-flight (was "" before the fix).
        ActivityEntity innerActivity = activityOf(processInstanceId, "inner");
        List<ProcessVariableEntity> innerScoped = variableRepository
            .findByProcessInstanceIdAndScopeId(processInstanceId, innerActivity.getId());
        assertThat(innerScoped.stream().filter(e -> e.getName().equals("innerSeen"))
            .map(ProcessVariableEntity::getTextValue))
            .as("innerSeen resolved down through the sub scope")
            .containsExactly("parent");

        runtimeService.completeServiceTask(openServiceTaskId(processInstanceId, "diff1-s027-inner"),
            List.of(var("innerOut", ProcessVariableType.STRING, "done")));
        runtimeService.completeServiceTask(openServiceTaskId(processInstanceId, "diff1-s027-after"),
            List.of(var("workResult", ProcessVariableType.STRING, "done")));

        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNotNull();

        // Fixed harness-visible set: the reported 4 plus subOut promoted to root on scope close.
        assertThat(rootVars(processInstanceId).keySet())
            .containsExactlyInAnyOrder("parentVar", "innerOut", "nullOut", "workResult", "subOut");
        assertThat(rootVars(processInstanceId)).containsEntry("subOut", "parent");
        // Residual DIFFER (п.3, by decision): dead-scope locals stay out of root.
        assertThat(rootVars(processInstanceId).keySet())
            .doesNotContain("subLocal", "innerSeen", "lateRead");
        // The closed sub scope is cleaned like any completed task scope.
        assertThat(variableRepository.findByProcessInstanceIdAndScopeId(processInstanceId, subActivity.getId()))
            .as("closed sub scope cleaned").isEmpty();
    }
}
