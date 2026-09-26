package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.query.VariableQuery;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
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
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-DIFF-3 — Raxon findings #4/#5/#6 against the Zorro MI output-collection path.
 *
 * <p>Mirrors the Raxon corpus scenarios S-029-multi-instance-happy (3 items, identity
 * {@code outputElement="=item"}) and S-030-multi-instance-empty ({@code items=[]}):
 * <ul>
 *   <li>#4: {@code outputCollection} must assemble POSITIONALLY by {@code loopCounter},
 *       not in job-completion order (Zeebe/Raxon: {@code ["a","b","c"]}; Zorro wrote
 *       {@code ["c","b","a"]} on reverse completion).</li>
 *   <li>#5: the internal batch variable {@code _mi_batch_<miId>} must not leak into
 *       the externally visible variables.</li>
 *   <li>#6: an empty {@code inputCollection} must still create the output variable
 *       as an empty array ({@code results=[]}), not skip it.</li>
 * </ul>
 *
 * <p>POF mutations (each REDs exactly its own test, named in the test javadoc):
 * <ul>
 *   <li>#4: route the MI aggregate back through completion-order append
 *       ({@code appendToJsonList}) instead of the positional write.</li>
 *   <li>#5: drop the internal-variable exclusion from the variables query.</li>
 *   <li>#6: drop the empty-array write on the zero-instance MI entry path.</li>
 * </ul>
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class MiOutputCollectionDiffIntegrationTests {

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

    private UUID startWithItems(String bpmnFile, String itemsJson) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + bpmnFile));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        ProcessVariable items = new ProcessVariable();
        items.setName("items");
        items.setType(ProcessVariableType.JSON);
        items.setValue(itemsJson);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of(items));
        return runtimeService.startProcessInstance(dto).getId();
    }

    private List<ActivityEntity> miTasks(UUID processInstanceId) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .filter(a -> a.getBpmnElementId().equals("miTask") && a.getStatus() == ActivityStatus.CREATED)
            .toList();
    }

    /** Scoped {@code loopCounter} (1-based) of one MI instance activity. */
    private int loopCounterOf(UUID processInstanceId, UUID activityId) {
        return variableRepository.findByProcessInstanceIdAndScopeId(processInstanceId, activityId).stream()
            .filter(v -> "loopCounter".equals(v.getName()))
            .findFirst()
            .map(v -> Integer.parseInt(v.getTextValue()))
            .orElseThrow(() -> new AssertionError("No scoped loopCounter on " + activityId));
    }

    private VariableQuery queryFor(UUID processInstanceId) {
        VariableQuery q = new VariableQuery();
        q.setPageIndex(0);
        q.setPageSize(50);
        q.setProcessInstanceId(processInstanceId);
        return q;
    }

    /**
     * #4: completing the 3 MI instances in REVERSE index order must still yield
     * the positional collection. POF mutation: completion-order append in the MI
     * aggregate → results {@code [60,40,20]} instead of {@code [20,40,60]}.
     */
    @Transactional
    @Test
    void outputCollection_assemblesPositionallyOnReverseCompletionOrder() throws Exception {
        // items = [10,20,30], outputElement == item*2 → positional [20,40,60].
        UUID pi = startWithItems("test-multi-instance-output.bpmn", "[10,20,30]");

        List<ActivityEntity> tasks = miTasks(pi);
        assertThat(tasks).hasSize(3);

        // Complete strictly LAST-spawned first (descending loopCounter).
        Map<UUID, Integer> slotByActivity = tasks.stream()
            .collect(Collectors.toMap(ActivityEntity::getId, a -> loopCounterOf(pi, a.getId())));
        assertThat(slotByActivity.values()).containsExactlyInAnyOrder(1, 2, 3);
        tasks.stream()
            .sorted(Comparator.<ActivityEntity, Integer>comparing(t -> slotByActivity.get(t.getId())).reversed())
            .forEach(t -> runtimeService.completeUserTask(t.getId(), List.of()));

        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();

        ProcessVariableEntity results = variableRepository
            .findByNameAndProcessInstanceId("doubled", pi).orElseThrow();
        List<Integer> values = new ObjectMapper().readValue(results.getTextValue(),
            new tools.jackson.core.type.TypeReference<List<Integer>>() {});
        assertThat(values).as("positional assembly, not completion order").containsExactly(20, 40, 60);
    }

    /**
     * #5: the internal {@code _mi_batch_miTask} bookkeeping row stays in storage
     * (WO-ENG-7 join isolation depends on it) but is invisible through the external
     * variables API. POF mutation: drop the exclusion from the variables query →
     * the internal name shows up in the API page.
     */
    @Transactional
    @Test
    void internalBatchVariable_hiddenFromVariablesApiButKeptInStorage() throws Exception {
        UUID pi = startWithItems("test-multi-instance-output.bpmn", "[10,20,30]");

        List<ActivityEntity> tasks = miTasks(pi);
        assertThat(tasks).hasSize(3);
        tasks.forEach(t -> runtimeService.completeUserTask(t.getId(), List.of()));

        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();

        PagedDataDTO<ProcessVariable> page = queryService.findVariables(queryFor(pi), null);
        assertThat(page.getData()).extracting(ProcessVariable::getName)
            .as("no internal bookkeeping in the external variables page")
            .doesNotContain("_mi_batch_miTask");

        assertThat(variableRepository.findByNameAndProcessInstanceId("_mi_batch_miTask", pi))
            .as("batch row itself is untouched in storage (WO-ENG-7)")
            .isPresent();
    }

    /**
     * #6: an empty input collection completes immediately AND still creates the
     * output variable as an empty array. POF mutation: drop the empty-array write
     * on the zero-instance entry path → {@code results} absent.
     */
    @Transactional
    @Test
    void emptyInputCollection_writesEmptyOutputArray() throws Exception {
        UUID pi = startWithItems("test-mi-empty-output.bpmn", "[]");

        assertThat(miTasks(pi)).as("zero MI instances spawned").isEmpty();
        assertThat(queryService.getProcessInstance(pi).getCompletedAt())
            .as("empty MI completes immediately").isNotNull();

        ProcessVariableEntity results = variableRepository
            .findByNameAndProcessInstanceId("results", pi).orElseThrow();
        assertThat(results.getType()).isEqualTo(ProcessVariableType.JSON);
        List<?> values = new ObjectMapper().readValue(results.getTextValue(), List.class);
        assertThat(values).as("results=[] exists and is empty").isEmpty();
    }
}
