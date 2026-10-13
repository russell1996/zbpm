package com.zorrodev.bpm.engine.integration;

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
import com.zorrodev.bpm.engine.repository.VariableRepository;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-41 — матрица паритета «Zeebe 8.5.0 × ZorroBPM» для MI subProcess.
 *
 * <p>Каждый тест — зеркало oracle-сценария S0–S9 из
 * {@code docs/analysis/c8-recon-mi-subprocess-2026-10-13.md} (там же дословные
 * логи живого Zeebe); ожидаемые значения — ровно те, что наблюдались на стенде.
 * Фикстуры {@code test-c841-parity-*.bpmn} — те же BPMN, что в
 * {@code docs/analysis/c8-oracle/bpmn/}, плюс {@code zeebe:taskDefinition type}
 * (нашему движку нужен тип джобы).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class MiSubProcessCamunda8ParityIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private QueryService queryService;
    @Autowired
    private ActivityRepository activityRepository;
    @Autowired
    private VariableRepository variableRepository;
    @Autowired
    private IncidentRepository incidentRepository;
    @Autowired
    private com.zorrodev.bpm.engine.service.DBService dbService;
    @Autowired
    private com.zorrodev.bpm.engine.service.ActivityService activityService;
    @Autowired
    private com.zorrodev.bpm.engine.handler.ElementSupport elementSupport;

    private UUID deploy(String bpmnFile) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + bpmnFile));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        return model.getId();
    }

    private UUID start(UUID pdId) {
        return start(pdId, List.of());
    }

    /** WO-C8-41 HOLD r1 (F-A): start with preseeded variables. */
    private UUID start(UUID pdId, List<ProcessVariable> variables) {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        dto.setVariables(variables);
        return runtimeService.startProcessInstance(dto).getId();
    }

    private List<ActivityEntity> activities(UUID pi, String bpmnElementId, ActivityStatus status) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getBpmnElementId().equals(bpmnElementId) && a.getStatus() == status)
            .toList();
    }

    private long incidents(UUID pi) {
        var activityIds = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .map(ActivityEntity::getId)
            .collect(Collectors.toSet());
        return incidentRepository.findAll().stream()
            .filter(i -> activityIds.contains(i.getActivityId()))
            .count();
    }

    private static ProcessVariable pv(String name, ProcessVariableType type, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(type);
        v.setValue(value);
        return v;
    }

    /** Merge-read видимых переменных activity — тот же chain-контекст, что движок
     * ({@code visibleVariables}: root + chain + own; item/loopCounter лежат на
     * скоупе КОНТЕЙНЕРА-инстанса, а не на строке внутренней задачи). Null-safe
     * сборка (предобъявленные null ломают Collectors.toMap). */
    private Map<String, String> visible(UUID pi, UUID activityId) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        for (ProcessVariable v : elementSupport.visibleVariables(pi, activityId)) {
            result.put(v.getName(), v.getValue());
        }
        return result;
    }

    private Map<String, String> root(UUID pi) {
        return dbService.getVariables(pi).stream()
            .collect(Collectors.toMap(ProcessVariable::getName, ProcessVariable::getValue,
                (a, b) -> b, java.util.LinkedHashMap::new));
    }

    private List<String> resultsOf(UUID pi) throws Exception {
        ProcessVariableEntity results = variableRepository
            .findByNameAndProcessInstanceId("results", pi).orElseThrow();
        return new ObjectMapper().readValue(results.getTextValue(),
            new tools.jackson.core.type.TypeReference<List<String>>() {});
    }

    /**
     * S0 (контроль, Zeebe: tu виден в w2 и dump — без MI completion-переменные
     * идут downstream). Наш root-write это повторяет.
     */
    @Transactional
    @Test
    void s0_plainChain_completionVarsVisibleDownstream() throws Exception {
        UUID pdId = deploy("test-c841-parity-s0-plain.bpmn");
        UUID pi = start(pdId);

        List<ActivityEntity> w = activities(pi, "w", ActivityStatus.CREATED);
        assertThat(w).hasSize(1);
        runtimeService.completeServiceTask(w.get(0).getId(),
            List.of(pv("tu", ProcessVariableType.STRING, "tu-plain")));

        List<ActivityEntity> w2 = activities(pi, "w2", ActivityStatus.CREATED);
        assertThat(w2).hasSize(1);
        assertThat(visible(pi, w2.get(0).getId())).containsEntry("tu", "tu-plain");
        runtimeService.completeServiceTask(w2.get(0).getId(), List.of());

        List<ActivityEntity> dump = activities(pi, "dump", ActivityStatus.CREATED);
        assertThat(dump).hasSize(1);
        assertThat(visible(pi, dump.get(0).getId())).containsEntry("tu", "tu-plain");
        runtimeService.completeServiceTask(dump.get(0).getId(), List.of());

        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertThat(incidents(pi)).isZero();
    }

    /**
     * S1 (Zeebe: w2 видит СВОЙ tu — гонки нет; dump: results=[tu-a,tu-b,tu-c],
     * tu/item/loopCounter отсутствуют).
     */
    @Transactional
    @Test
    void s1_noRace_eachBranchSeesOwnTu_dumpSeesResultsOnly() throws Exception {
        UUID pdId = deploy("test-c841-parity-s1-race.bpmn");
        UUID pi = start(pdId);

        List<ActivityEntity> w = activities(pi, "w", ActivityStatus.CREATED);
        assertThat(w).as("3 parallel MI-subprocess instances").hasSize(3);
        Map<UUID, String> itemByJob = w.stream().collect(Collectors.toMap(ActivityEntity::getId,
            j -> visible(pi, j.getId()).get("item")));
        assertThat(itemByJob.values()).containsExactlyInAnyOrder("a", "b", "c");

        // Завершения в обратном порядке (c,b,a), как в oracle-прогоне.
        w.stream()
            .sorted(Comparator.<ActivityEntity, String>comparing(j -> itemByJob.get(j.getId())).reversed())
            .forEach(j -> runtimeService.completeServiceTask(j.getId(), List.of(
                pv("tu", ProcessVariableType.STRING, "tu-" + itemByJob.get(j.getId())))));

        List<ActivityEntity> w2 = activities(pi, "w2", ActivityStatus.CREATED);
        assertThat(w2).hasSize(3);
        for (ActivityEntity job : w2) {
            Map<String, String> vars = visible(pi, job.getId());
            assertThat(vars.get("tu")).as("branch sees its OWN tu (no race), item=" + vars.get("item"))
                .isEqualTo("tu-" + vars.get("item"));
        }
        w2.forEach(j -> runtimeService.completeServiceTask(j.getId(), List.of()));

        List<ActivityEntity> dump = activities(pi, "dump", ActivityStatus.CREATED);
        assertThat(dump).hasSize(1);
        Map<String, String> dumpVars = visible(pi, dump.get(0).getId());
        assertThat(dumpVars).as("dump sees no completion locals").doesNotContainKeys("tu", "item", "loopCounter");
        runtimeService.completeServiceTask(dump.get(0).getId(), List.of());

        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertThat(incidents(pi)).isZero();
        assertThat(resultsOf(pi)).containsExactly("tu-a", "tu-b", "tu-c");
    }

    /**
     * S1-1 (Zeebe: при входе предсозданы results=[null×N] и tu=null).
     */
    @Transactional
    @Test
    void s1_enter_precreatesResultsAndOutputName() throws Exception {
        UUID pdId = deploy("test-c841-parity-s1-race.bpmn");
        UUID pi = start(pdId);

        List<ActivityEntity> w = activities(pi, "w", ActivityStatus.CREATED);
        assertThat(w).hasSize(3);
        for (ActivityEntity job : w) {
            Map<String, String> vars = visible(pi, job.getId());
            assertThat(vars).as("item/loopCounter bound at entry").containsKeys("item", "loopCounter");
            assertThat(vars).as("results pre-created at entry").containsKey("results");
            List<String> results = new ObjectMapper().readValue(
                variableRepository.findByNameAndProcessInstanceId("results", pi)
                    .orElseThrow().getTextValue(), List.class);
            assertThat(results).as("results pre-created with one null slot per instance")
                .hasSize(3).containsOnly((String) null);
            // WO-C8-41 (F2-бис): скалярный null строкой непредставим (upserts
            // PG+H2 коэрсят null→"") — «не задано» = отсутствие ключа;
            // Zeebe показывает tu:null в том же смысле (значение null).
            assertThat(vars).as("outputElement name unset at entry").doesNotContainKey("tu");
        }
    }

    /**
     * S2a (Zeebe: output mapping tu→tu виден в w2 своей ветки).
     */
    @Transactional
    @Test
    void s2a_outputMapping_visibleInOwnBranch() throws Exception {
        UUID pdId = deploy("test-c841-parity-s2a-outputmap.bpmn");
        UUID pi = start(pdId);

        List<ActivityEntity> w = activities(pi, "w", ActivityStatus.CREATED);
        assertThat(w).hasSize(3);
        Map<UUID, String> itemByJob = w.stream().collect(Collectors.toMap(ActivityEntity::getId,
            j -> visible(pi, j.getId()).get("item")));
        w.stream()
            .sorted(Comparator.<ActivityEntity, String>comparing(j -> itemByJob.get(j.getId())).reversed())
            .forEach(j -> runtimeService.completeServiceTask(j.getId(), List.of(
                pv("tu", ProcessVariableType.STRING, "tu-" + itemByJob.get(j.getId())))));

        List<ActivityEntity> w2 = activities(pi, "w2", ActivityStatus.CREATED);
        assertThat(w2).hasSize(3);
        for (ActivityEntity job : w2) {
            Map<String, String> vars = visible(pi, job.getId());
            assertThat(vars.get("tu")).as("mapping result visible in own branch")
                .isEqualTo("tu-" + vars.get("item"));
        }
        w2.forEach(j -> runtimeService.completeServiceTask(j.getId(), List.of()));

        List<ActivityEntity> dump = activities(pi, "dump", ActivityStatus.CREATED);
        assertThat(dump).hasSize(1);
        runtimeService.completeServiceTask(dump.get(0).getId(), List.of());
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertThat(incidents(pi)).isZero();
        assertThat(resultsOf(pi)).containsExactly("tu-a", "tu-b", "tu-c");
    }

    /**
     * S2b (Zeebe: tuLocal предобъявлен null; сырой tu в скоупе не оседает;
     * w2 видит свой tuLocal; dump — только results).
     */
    @Transactional
    @Test
    void s2b_renamedMapping_rawTuNeverLandsInScope() throws Exception {
        UUID pdId = deploy("test-c841-parity-s2b-outputmap-renamed.bpmn");
        UUID pi = start(pdId);

        List<ActivityEntity> w = activities(pi, "w", ActivityStatus.CREATED);
        assertThat(w).hasSize(3);
        for (ActivityEntity job : w) {
            // WO-C8-41 (F2-бис): как выше — отсутствие вместо null-строки.
            assertThat(visible(pi, job.getId())).as("mapping target unset at entry")
                .doesNotContainKey("tuLocal");
        }
        Map<UUID, String> itemByJob = w.stream().collect(Collectors.toMap(ActivityEntity::getId,
            j -> visible(pi, j.getId()).get("item")));
        w.stream()
            .sorted(Comparator.<ActivityEntity, String>comparing(j -> itemByJob.get(j.getId())).reversed())
            .forEach(j -> runtimeService.completeServiceTask(j.getId(), List.of(
                pv("tu", ProcessVariableType.STRING, "tu-" + itemByJob.get(j.getId())))));

        List<ActivityEntity> w2 = activities(pi, "w2", ActivityStatus.CREATED);
        assertThat(w2).hasSize(3);
        for (ActivityEntity job : w2) {
            Map<String, String> vars = visible(pi, job.getId());
            assertThat(vars.get("tuLocal")).as("renamed mapping visible in own branch")
                .isEqualTo("tu-" + vars.get("item"));
        }
        w2.forEach(j -> runtimeService.completeServiceTask(j.getId(), List.of()));

        List<ActivityEntity> dump = activities(pi, "dump", ActivityStatus.CREATED);
        assertThat(dump).hasSize(1);
        Map<String, String> dumpVars = visible(pi, dump.get(0).getId());
        assertThat(dumpVars).as("raw tu never lands anywhere observable")
            .doesNotContainKeys("tu", "tuLocal", "item", "loopCounter");
        runtimeService.completeServiceTask(dump.get(0).getId(), List.of());

        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertThat(incidents(pi)).isZero();
        assertThat(resultsOf(pi)).containsExactly("tu-a", "tu-b", "tu-c");
    }

    /**
     * S3 (Zeebe: x элемент-локален — w2 видит x:null; item/loopCounter видны;
     * dump: results=[null,null] без утечек x/lc).
     */
    @Transactional
    @Test
    void s3_inputMappingResultStaysElementLocal() throws Exception {
        UUID pdId = deploy("test-c841-parity-s3-inputmap.bpmn");
        UUID pi = start(pdId);

        List<ActivityEntity> w = activities(pi, "w", ActivityStatus.CREATED);
        assertThat(w).hasSize(2);
        for (ActivityEntity job : w) {
            Map<String, String> vars = visible(pi, job.getId());
            assertThat(vars.get("x")).as("input mapping =item -> x works").isEqualTo(vars.get("item"));
        }
        w.forEach(j -> runtimeService.completeServiceTask(j.getId(), List.of()));

        List<ActivityEntity> w2 = activities(pi, "w2", ActivityStatus.CREATED);
        assertThat(w2).hasSize(2);
        for (ActivityEntity job : w2) {
            Map<String, String> vars = visible(pi, job.getId());
            assertThat(vars).as("x does not cross w -> w2").doesNotContainKey("x");
            assertThat(vars.get("lc")).as("loopCounter mappable in w2")
                .isEqualTo(vars.get("loopCounter"));
        }
        w2.forEach(j -> runtimeService.completeServiceTask(j.getId(), List.of()));

        List<ActivityEntity> dump = activities(pi, "dump", ActivityStatus.CREATED);
        assertThat(dump).hasSize(1);
        Map<String, String> dumpVars = visible(pi, dump.get(0).getId());
        assertThat(dumpVars).as("no x/lc leakage to parent").doesNotContainKeys("x", "lc");
        runtimeService.completeServiceTask(dump.get(0).getId(), List.of());

        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertThat(incidents(pi)).isZero();
        ProcessVariableEntity results = variableRepository
            .findByNameAndProcessInstanceId("results", pi).orElseThrow();
        assertThat(new ObjectMapper().readValue(results.getTextValue(), List.class))
            .containsExactly(null, null);
    }

    /**
     * S5 (Zeebe: необновлённый outputElement даёт null в слоте — [null,null]).
     */
    @Transactional
    @Test
    void s5_unsetOutputElement_collectsNulls() throws Exception {
        UUID pdId = deploy("test-c841-parity-s5-unset-output.bpmn");
        UUID pi = start(pdId);

        List<ActivityEntity> w = activities(pi, "w", ActivityStatus.CREATED);
        assertThat(w).hasSize(2);
        w.forEach(j -> runtimeService.completeServiceTask(j.getId(), List.of()));

        List<ActivityEntity> dump = activities(pi, "dump", ActivityStatus.CREATED);
        assertThat(dump).hasSize(1);
        runtimeService.completeServiceTask(dump.get(0).getId(), List.of());

        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertThat(incidents(pi)).isZero();
        ProcessVariableEntity results = variableRepository
            .findByNameAndProcessInstanceId("results", pi).orElseThrow();
        assertThat(new ObjectMapper().readValue(results.getTextValue(), List.class))
            .containsExactly(null, null);
    }

    /**
     * S10 (WO-C8-41 HOLD r1, F-A; Zeebe 8.5.0, repro scratchpad/s10): при входе
     * в MI-тело Zeebe ВСЕГДА создаёт outputCollection заново [null×N] —
     * одноимённая предустановленная переменная перезаписывается, хвост не
     * протекает. Preseeded results=["stale"×3], MI по 2 элементам (s5-фикстура,
     * outputElement unset) → в конце [null, null].
     */
    @Transactional
    @Test
    void s10_preseededOutputCollectionOverwrittenAtEntry() throws Exception {
        UUID pdId = deploy("test-c841-parity-s5-unset-output.bpmn");
        UUID pi = start(pdId, List.of(pv("results", ProcessVariableType.JSON,
            "[\"stale\",\"stale\",\"stale\"]")));

        List<ActivityEntity> w = activities(pi, "w", ActivityStatus.CREATED);
        assertThat(w).hasSize(2);
        // F-A: перезапись при входе — fresh [null×2] вместо пропуска из-за
        // существующей переменной.
        assertThat(resultsOf(pi)).as("outputCollection re-created fresh at entry (F-A)")
            .containsExactly(null, null);
        w.forEach(j -> runtimeService.completeServiceTask(j.getId(), List.of()));

        List<ActivityEntity> dump = activities(pi, "dump", ActivityStatus.CREATED);
        assertThat(dump).hasSize(1);
        runtimeService.completeServiceTask(dump.get(0).getId(), List.of());

        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertThat(incidents(pi)).isZero();
        assertThat(resultsOf(pi)).as("no stale tail leaks into the aggregated collection")
            .containsExactly(null, null);
    }

    /**
     * S7 (Zeebe: прерывающая граница — job-ы отменены, results в родителя
     * НЕ передаётся; dumpCancel видит только свой path-маркер).
     * Триггер здесь — message-граница (детерминированная замена PT3S-таймера
     * oracle-прогона; путь отмены тот же).
     */
    @Transactional
    @Test
    void s7_interruptingBoundary_noResultsPropagated() throws Exception {
        UUID pdId = deploy("test-c841-parity-s7-boundary.bpmn");
        UUID pi = start(pdId);

        assertThat(activities(pi, "w", ActivityStatus.CREATED))
            .as("2 live instance jobs before the boundary").hasSize(2);

        activityService.correlateMessage("c841-cancel", pi, List.of());

        assertThat(activities(pi, "w", ActivityStatus.CREATED))
            .as("instance jobs canceled").isEmpty();
        assertThat(activities(pi, "miSub", ActivityStatus.CANCELLED)).hasSize(2);
        List<ActivityEntity> dump = activities(pi, "dumpCancel", ActivityStatus.CREATED);
        assertThat(dump).as("cancel branch taken").hasSize(1);
        Map<String, String> dumpVars = visible(pi, dump.get(0).getId());
        assertThat(dumpVars).as("cancel branch marker").containsEntry("path", "cancel");
        assertThat(dumpVars).as("no results on the cancel path").doesNotContainKey("results");
        assertThat(root(pi)).as("no results in root either").doesNotContainKey("results");
        runtimeService.completeServiceTask(dump.get(0).getId(), List.of());

        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertThat(incidents(pi)).isZero();
    }

    /**
     * S8a (Zeebe: oitem виден внутри; внутренний loopCounter затеняет внешний;
     * dump: outerResults=[[1,2],[1,2]], innerResults отсутствует).
     */
    @Transactional
    @Test
    void s8a_nested_outerItemVisible_innerLoopCounterShadows() throws Exception {
        UUID pdId = deploy("test-c841-parity-s8a-nested.bpmn");
        UUID pi = start(pdId);

        List<ActivityEntity> w = activities(pi, "w", ActivityStatus.CREATED);
        assertThat(w).as("2 outer x 2 inner jobs").hasSize(4);
        for (ActivityEntity job : w) {
            Map<String, String> vars = visible(pi, job.getId());
            assertThat(vars.get("oitem")).as("outer item visible inside").isIn("x", "y");
            assertThat(vars).as("inner item bound").containsKeys("iitem", "loopCounter");
        }
        w.forEach(j -> runtimeService.completeServiceTask(j.getId(), List.of()));

        List<ActivityEntity> dump = activities(pi, "dump", ActivityStatus.CREATED);
        assertThat(dump).hasSize(1);
        Map<String, String> dumpVars = visible(pi, dump.get(0).getId());
        assertThat(dumpVars).as("inner aggregation does not leak").doesNotContainKey("innerResults");
        runtimeService.completeServiceTask(dump.get(0).getId(), List.of());

        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertThat(incidents(pi)).isZero();
        ProcessVariableEntity outer = variableRepository
            .findByNameAndProcessInstanceId("outerResults", pi).orElseThrow();
        assertThat(new ObjectMapper().readValue(outer.getTextValue(), List.class))
            .containsExactly(List.of("1", "2"), List.of("1", "2"));
    }

    /**
     * S8b (Zeebe: внешний item виден MI-задаче внутри; dump: results=[[p,q],[p,q]],
     * tresults отсутствует).
     */
    @Transactional
    @Test
    void s8b_miTaskInMiSub_outerItemVisible_noInnerLeak() throws Exception {
        UUID pdId = deploy("test-c841-parity-s8b-task-in-sub.bpmn");
        UUID pi = start(pdId);

        List<ActivityEntity> w = activities(pi, "mtask", ActivityStatus.CREATED);
        assertThat(w).as("2 outer x 2 inner MI-task jobs").hasSize(4);
        for (ActivityEntity job : w) {
            Map<String, String> vars = visible(pi, job.getId());
            assertThat(vars.get("item")).as("outer item visible to inner MI task").isIn("a", "b");
            assertThat(vars.get("pitem")).as("inner item bound").isIn("p", "q");
        }
        w.forEach(j -> runtimeService.completeServiceTask(j.getId(), List.of()));

        List<ActivityEntity> dump = activities(pi, "dump", ActivityStatus.CREATED);
        assertThat(dump).hasSize(1);
        Map<String, String> dumpVars = visible(pi, dump.get(0).getId());
        assertThat(dumpVars).as("inner tresults does not leak").doesNotContainKey("tresults");
        runtimeService.completeServiceTask(dump.get(0).getId(), List.of());

        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertThat(incidents(pi)).isZero();
        ProcessVariableEntity results = variableRepository
            .findByNameAndProcessInstanceId("results", pi).orElseThrow();
        assertThat(new ObjectMapper().readValue(results.getTextValue(), List.class))
            .containsExactly(List.of("p", "q"), List.of("p", "q"));
    }

    /**
     * S9 (Zeebe: =numberOfCompletedInstances >= 1 терминирует оставшиеся
     * инстансы; частичный results передаётся — ["a",null,null] при завершении
     * первого).
     */
    @Transactional
    @Test
    void s9_completionCondition_terminatesSiblings_partialResultsPropagated() throws Exception {
        UUID pdId = deploy("test-c841-parity-s9-completion-cond.bpmn");
        UUID pi = start(pdId);

        List<ActivityEntity> w = activities(pi, "w", ActivityStatus.CREATED);
        assertThat(w).as("3 instances at entry").hasSize(3);
        String firstItem = visible(pi, w.get(0).getId()).get("item");
        runtimeService.completeServiceTask(w.get(0).getId(), List.of());

        assertThat(activities(pi, "w", ActivityStatus.CREATED))
            .as("remaining siblings terminated by the condition").isEmpty();

        List<ActivityEntity> dump = activities(pi, "dump", ActivityStatus.CREATED);
        assertThat(dump).as("MI body completes early").hasSize(1);
        runtimeService.completeServiceTask(dump.get(0).getId(), List.of());

        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertThat(incidents(pi)).isZero();
        ProcessVariableEntity results = variableRepository
            .findByNameAndProcessInstanceId("results", pi).orElseThrow();
        List<String> values = new ObjectMapper().readValue(results.getTextValue(),
            new tools.jackson.core.type.TypeReference<List<String>>() {});
        assertThat(values).as("completed slot holds its item, terminated slots stay null")
            .hasSize(3).contains(firstItem);
        assertThat(values.stream().filter(v -> v != null).count())
            .as("exactly one slot filled").isEqualTo(1);
    }
}
