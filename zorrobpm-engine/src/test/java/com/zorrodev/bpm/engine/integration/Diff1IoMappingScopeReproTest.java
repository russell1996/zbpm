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
    private IncidentRepository incidentRepository;

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

    private com.zorrodev.bpm.contract.dto.query.IncidentQuery openIncidents(UUID pi) {
        com.zorrodev.bpm.contract.dto.query.IncidentQuery q =
            new com.zorrodev.bpm.contract.dto.query.IncidentQuery();
        q.setProcessInstanceId(pi);
        q.setResolved(false);
        return q;
    }

    private com.zorrodev.bpm.contract.dto.Incident singleOpenIncident(UUID pi, String what) {
        // WO-ENG-29: IncidentService.raiseIncident пишет fallback-activity, когда
        // живой activity уже завершён (см. WO-REL-40) — IncidentQuery-фильтр по
        // processInstanceId идёт через activities-подзапрос и такие инциденты
        // НЕ ВИДИТ. Поэтому собираем activity-ids инстанса и читаем напрямую
        // findByActivityIdInAndCompletedAtIsNull (индексный путь, не findAll-скан).
        List<UUID> activityIds = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .map(ActivityEntity::getId)
            .toList();
        List<com.zorrodev.bpm.engine.entity.IncidentEntity> incidents =
            incidentRepository.findByActivityIdInAndCompletedAtIsNull(activityIds);
        assertThat(incidents).as(what).hasSize(1);
        com.zorrodev.bpm.contract.dto.Incident dto =
            new com.zorrodev.bpm.contract.dto.Incident();
        dto.setId(incidents.get(0).getId());
        return dto;
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

        // WO-ENG-29: inner output =noSuchVar -> nullOut references a variable absent
        // from the job result — since ENG-29 this is a FEEL evaluation FAILURE and
        // raises an incident on the inner task (before: silent "" nullOut). The
        // completion below therefore parks instead of advancing: assert the
        // incident explicitly, then resolve it with the missing variable supplied
        // (operator fixes data and re-runs — the incident-resolve path), after
        // which the flow continues exactly as this test asserted before.
        runtimeService.completeServiceTask(openServiceTaskId(processInstanceId, "diff1-s027-inner"),
            List.of(var("innerOut", ProcessVariableType.STRING, "done")));
        // findIncidents(pageable=null) — см. сервисные тесты: null-pageable допустим.
        com.zorrodev.bpm.contract.dto.Incident incident =
            singleOpenIncident(processInstanceId,
                "ENG-29: output mapping on absent 'noSuchVar' raises an incident, not silent ''");
        runtimeService.resolveIncident(incident.getId(),
            List.of(var("noSuchVar", ProcessVariableType.STRING, "nowhere")));
        runtimeService.completeServiceTask(openServiceTaskId(processInstanceId, "diff1-s027-inner"),
            List.of(var("innerOut", ProcessVariableType.STRING, "done")));
        // WO-ENG-29 second-order effect (documented): sub-close cleans the sub scope
        // (WO-ENG-14 decision), so the after-task's `=subLocal -> lateRead` input now
        // ALSO fails with NO_VARIABLE_FOUND and parks as an incident (before ENG-29
        // it silently wrote ""). The incident parks at ENTER time, so the after-task
        // has no open service-task row yet (openServiceTaskId would find nothing) —
        // resolve directly, then complete the re-entered job below.
        com.zorrodev.bpm.contract.dto.Incident afterIncident = singleOpenIncident(processInstanceId,
            "ENG-29: after-task input on cleaned sub-scope variable also incidents");
        runtimeService.resolveIncident(afterIncident.getId(),
            List.of(var("subLocal", ProcessVariableType.STRING, "parent")));
        // Resolve re-executes the after-task (fresh job) — complete it with the
        // worker's result; the input mapping now evaluates against the supplied
        // subLocal and the flow finishes.
        runtimeService.completeServiceTask(openServiceTaskId(processInstanceId, "diff1-s027-after"),
            List.of(var("workResult", ProcessVariableType.STRING, "done")));

        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNotNull();

        // Fixed harness-visible set: the reported 4 plus subOut promoted to root on scope close.
        // WO-ENG-29: nullOut now carries the resolve-supplied value ("nowhere"), not the
        // pre-ENG-29 silent "" — the KEY is present either way; the incident above is
        // the proof the old silent path is gone. NOTE on lateRead: after-task input
        // mapping writes at TASK scope and completion wipes task scope (WO-ENG-14
        // lifecycle) — lateRead never reaches root, same as orderIdCopy in finding1
        // above. The resolve value ("parent") did its job mid-flight (input evaluated
        // cleanly, no second incident, flow finished).
        // NOTE: subLocal IS in root here — supplied by the operator at resolve time
        // (resolveIncident variables are written at ROOT scope by design), not
        // promoted from the cleaned sub scope. innerSeen (never re-supplied) stays out.
        assertThat(rootVars(processInstanceId).keySet())
            .containsExactlyInAnyOrder("parentVar", "innerOut", "nullOut", "workResult", "subOut",
                "noSuchVar", "subLocal");
        assertThat(rootVars(processInstanceId)).containsEntry("subOut", "parent");
        // Residual DIFFER (п.3, by decision): dead-scope locals stay out of root —
        // innerSeen was never re-supplied, so it is still absent (subLocal above is
        // present only because the operator re-supplied it at resolve time).
        assertThat(rootVars(processInstanceId).keySet())
            .doesNotContain("innerSeen");
        // The closed sub scope is cleaned like any completed task scope.
        assertThat(variableRepository.findByProcessInstanceIdAndScopeId(processInstanceId, subActivity.getId()))
            .as("closed sub scope cleaned").isEmpty();
    }

    @Transactional
    @Test
    void hold_nestedSubInSub_bothIoMappingsParsedSeededAndPromoted() throws Exception {
        // WO-DIFF-1 HOLD (CTO counter-example, verified by isolated run of the same logic):
        // <subProcess id="outer">…<subProcess id="inner">…</subProcess>…</subProcess>.
        // The old scanner found outer first (mine=false when searching inner), took the
        // INNERMOST "</bpmn:subProcess>" as outer's close, and leapt clean over inner's
        // open tag — inner's ioMapping came back null silently (no log, no exception).
        // WO-C8-14b supports subProcess-in-subProcess as a first-class construct, so this
        // is a real gap, not hypothetical.
        String bpmn = Files.readString(Paths.get("src/test/files/test-diff1-nested-sub.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        // Parse: BOTH containers carry their own ioMapping, extracted independently.
        // (Before the fix: outer parsed, inner null — the exact HOLD symptom.)
        BpmnProcessDefinitionModel parsed = bpmnService.getProcessDefinitionModelById(model.getId());
        BpmnElementModel outer = parsed.getElement("outer");
        BpmnElementModel inner = parsed.getElement("inner");
        assertThat(outer).as("parsed outer element").isNotNull();
        assertThat(inner).as("parsed inner element").isNotNull();
        assertThat(outer.getExtensions().getIoMappingExtension())
            .as("outer ioMapping parsed").isNotNull();
        assertThat(outer.getExtensions().getIoMappingExtension().getInputs())
            .as("outer input mappings").hasSize(1);
        assertThat(outer.getExtensions().getIoMappingExtension().getOutputs())
            .as("outer output mappings").hasSize(1);
        assertThat(inner.getExtensions().getIoMappingExtension())
            .as("inner ioMapping parsed").isNotNull();
        assertThat(inner.getExtensions().getIoMappingExtension().getInputs())
            .as("inner input mappings").hasSize(1);
        assertThat(inner.getExtensions().getIoMappingExtension().getOutputs())
            .as("inner output mappings").hasSize(1);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of(var("outerRoot", ProcessVariableType.STRING, "r")));
        UUID processInstanceId = runtimeService.startProcessInstance(dto).getId();

        // Runtime: each level's input mapping is seeded into its own scope at entry.
        ActivityEntity outerActivity = activityOf(processInstanceId, "outer");
        assertThat(variableRepository
            .findByProcessInstanceIdAndScopeId(processInstanceId, outerActivity.getId()).stream()
            .filter(e -> e.getName().equals("outerLocal"))
            .map(ProcessVariableEntity::getTextValue))
            .as("seeded outerLocal at outer scope")
            .containsExactly("r");
        ActivityEntity innerActivity = activityOf(processInstanceId, "inner");
        assertThat(variableRepository
            .findByProcessInstanceIdAndScopeId(processInstanceId, innerActivity.getId()).stream()
            .filter(e -> e.getName().equals("innerLocal"))
            .map(ProcessVariableEntity::getTextValue))
            .as("seeded innerLocal at inner scope (resolved down through outer)")
            .containsExactly("r");

        // Visibility down two levels: the innermost task input =innerLocal -> workSeen
        // resolves through the inner scope mid-flight (was "" before the p.1 fix family).
        ActivityEntity workActivity = activityOf(processInstanceId, "work");
        assertThat(variableRepository
            .findByProcessInstanceIdAndScopeId(processInstanceId, workActivity.getId()).stream()
            .filter(e -> e.getName().equals("workSeen"))
            .map(ProcessVariableEntity::getTextValue))
            .as("workSeen resolved down through inner+outer scopes")
            .containsExactly("r");

        runtimeService.completeServiceTask(openServiceTaskId(processInstanceId, "diff1-nested-work"),
            List.of(var("workResult", ProcessVariableType.STRING, "done")));

        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNotNull();

        // Both levels' outputs promoted to root on scope close (inner first, then outer —
        // outer's source still resolves because the outer scope is alive at its own close).
        assertThat(rootVars(processInstanceId).keySet())
            .containsExactlyInAnyOrder("outerRoot", "workResult", "workPromoted", "innerOut", "outerOut");
        assertThat(rootVars(processInstanceId))
            .containsEntry("workPromoted", "r")
            .containsEntry("innerOut", "r")
            .containsEntry("outerOut", "r");
        // Both closed scopes cleaned like any completed task scope.
        assertThat(variableRepository.findByProcessInstanceIdAndScopeId(processInstanceId, innerActivity.getId()))
            .as("closed inner scope cleaned").isEmpty();
        assertThat(variableRepository.findByProcessInstanceIdAndScopeId(processInstanceId, outerActivity.getId()))
            .as("closed outer scope cleaned").isEmpty();
    }
}
