package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import com.zorrodev.bpm.engine.service.db.VariableDbOperations;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-PERF-9 (B-8, full-scan): MI transitions read only the FEEL-referenced
 * variables — one indexed SELECT per transition — never the whole instance
 * scope.
 * <p>
 * Proof is at the SQL level, not just a Java assert: with hundreds of junk
 * variables in the scope, the prod pinpoint methods return exactly the named
 * rows (row-count on the REAL prod path — {@link VariableDbOperations} →
 * {@link VariableRepository}), and EXPLAIN on the same predicate shape shows
 * an index plan (no Seq Scan). Behaviour is proven end-to-end (parallel,
 * sequential, output aggregation): a broken name-extraction would miscount
 * cardinality or corrupt the output — an incident/0 tasks, not silence.
 * <p>
 * Tagged {@code @Tag("pg")} via PostgresIT — excluded from default CI runs.
 */
public class MiFullScanPgIT extends PostgresIT {

    /** Junk variables per instance — wide enough to prove filtering matters. */
    private static final int JUNK_VARS = 300;

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
    private VariableDbOperations variableDbOperations;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    private void ensureTx() {
        if (tx == null) tx = new TransactionTemplate(txManager);
    }

    private static ProcessVariable pv(String name, String value, ProcessVariableType type) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setValue(value);
        v.setType(type);
        return v;
    }

    /** Starts the MI process with the collection plus {@value #JUNK_VARS} junk root variables. */
    private UUID startMiWithJunk(String bpmnFile, String collectionValue) {
        ensureTx();
        return tx.execute(s -> {
            try {
                String bpmn = Files.readString(Paths.get("src/test/files/" + bpmnFile));
                ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
                List<ProcessVariable> vars = new ArrayList<>();
                vars.add(pv("items", collectionValue, ProcessVariableType.JSON));
                for (int i = 0; i < JUNK_VARS; i++) {
                    vars.add(pv("junk_" + i, "noise-" + i, ProcessVariableType.STRING));
                }
                StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
                dto.setProcessDefinitionId(model.getId());
                dto.setVariables(vars);
                return runtimeService.startProcessInstance(dto).getId();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private List<ActivityEntity> miTasks(UUID piId, ActivityStatus status) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(piId))
            .filter(a -> a.getBpmnElementId().equals("miTask"))
            .filter(a -> a.getStatus() == status)
            .toList();
    }

    private void completeAllMiTasks(UUID piId) {
        // Each completion re-reads the collection pinpoint (sequential path)
        // or evaluates the completion/output pinpoint (parallel path).
        List<ActivityEntity> tasks = miTasks(piId, ActivityStatus.CREATED);
        for (ActivityEntity t : tasks) {
            ensureTx();
            tx.execute(s -> {
                runtimeService.completeUserTask(t.getId(), List.of());
                return null;
            });
        }
    }

    /**
     * Parallel MI over a 3-item collection surrounded by 300 junk variables:
     * exactly 3 instances spawn (cardinality eval saw `items` through the
     * pinpoint read — a missed name would incident with 0 tasks) and the
     * instance completes after all three completions.
     */
    @Test
    void parallelMi_withJunkVars_spawnsExactCardinalityAndCompletes() {
        UUID piId = startMiWithJunk("test-multi-instance-element.bpmn", "[10,20,30]");

        assertThat(miTasks(piId, ActivityStatus.CREATED))
            .as("3 MI instances for a 3-item collection despite 300 junk vars")
            .hasSize(3);

        completeAllMiTasks(piId);

        ensureTx();
        Boolean completed = tx.execute(s ->
            queryService.getProcessInstance(piId).getCompletedAt() != null);
        assertThat(completed).as("process completes after all MI instances").isTrue();
    }

    /**
     * Output aggregation through the pinpoint path: the outputElement
     * expression evaluates against only its referenced names and the slot
     * comes from the loopCounter-only read — the aggregated collection is
     * exactly right despite 300 junk variables in the scope.
     */
    @Test
    void parallelMiOutput_withJunkVars_aggregatesExactly() {
        UUID piId = startMiWithJunk("test-multi-instance-output.bpmn", "[10,20,30]");

        assertThat(miTasks(piId, ActivityStatus.CREATED)).hasSize(3);
        completeAllMiTasks(piId);

        ProcessVariableEntity doubled = variableRepository
            .findByNameAndProcessInstanceId("doubled", piId).orElseThrow();
        assertThat(doubled.getType()).isEqualTo(ProcessVariableType.JSON);
        // PG jsonb ::text normalises separators — compare parsed, not raw text.
        List<Integer> values;
        try {
            values = new tools.jackson.databind.ObjectMapper().readValue(doubled.getTextValue(),
                new tools.jackson.core.type.TypeReference<List<Integer>>() {
                });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertThat(values).containsExactlyInAnyOrder(20, 40, 60);
    }

    /**
     * Sequential MI re-evaluates the inputCollection on every continue
     * transition ({@code miInputCollection}) — 3 sequential instances run to
     * completion with 300 junk variables present at every step.
     */
    @Test
    void sequentialMi_withJunkVars_runsAllInstancesToCompletion() {
        UUID piId = startMiWithJunk("test-perf-9-mi-sequential-collection.bpmn", "[10,20,30]");

        // Sequential: exactly 1 active at a time; each completion spawns the next.
        for (int i = 0; i < 3; i++) {
            assertThat(miTasks(piId, ActivityStatus.CREATED))
                .as("exactly one sequential instance active at step " + i)
                .hasSize(1);
            ActivityEntity current = miTasks(piId, ActivityStatus.CREATED).get(0);
            ensureTx();
            tx.execute(s -> {
                runtimeService.completeUserTask(current.getId(), List.of());
                return null;
            });
        }

        ensureTx();
        Boolean completed = tx.execute(s ->
            queryService.getProcessInstance(piId).getCompletedAt() != null);
        assertThat(completed).as("sequential MI process completes after all 3 instances").isTrue();
        assertThat(miTasks(piId, ActivityStatus.COMPLETED)).hasSize(3);
    }

    /**
     * SQL-level proof on the REAL prod path: with 301 root rows present, the
     * pinpoint root read returns exactly the 1 named row (the method has no
     * Java-side filter — everything not returned was excluded by the SQL
     * WHERE), and the scoped pinpoint returns exactly the loopCounter row.
     */
    @Test
    void pinpointReads_returnOnlyNamedRowsDespiteJunkScope() {
        UUID piId = startMiWithJunk("test-multi-instance-element.bpmn", "[10,20,30]");

        List<ProcessVariable> items = variableDbOperations.getVariablesByNames(piId, Set.of("items"));
        assertThat(items).as("pinpoint root read returns exactly the named row").hasSize(1);
        assertThat(items.get(0).getValue()).isEqualTo("[10,20,30]");

        List<ProcessVariable> all = variableDbOperations.getVariables(piId);
        assertThat(all.size())
            .as("control: the full scope really holds items + junk + batch var")
            .isGreaterThanOrEqualTo(JUNK_VARS + 1);

        UUID scope = miTasks(piId, ActivityStatus.CREATED).get(0).getId();
        List<ProcessVariable> slot = variableDbOperations.getScopedVariablesByNames(piId, scope, Set.of("loopCounter"));
        assertThat(slot).as("scoped pinpoint returns exactly the loopCounter row").hasSize(1);
        assertThat(slot.get(0).getName()).isEqualTo("loopCounter");
        // findAll order is arbitrary — this scope holds whichever instance it holds.
        assertThat(slot.get(0).getValue()).isIn("1", "2", "3");
    }

    /**
     * Planner-level proof: the pinpoint predicate shape
     * ({@code process_instance_id = ? AND scope_id IS NULL AND name IN (...)})
     * resolves via the unique index — no Seq Scan even with hundreds of rows
     * in the table.
     */
    @Test
    void pinpointPredicateShape_usesIndexNoSeqScan() {
        UUID piId = startMiWithJunk("test-multi-instance-element.bpmn", "[10,20,30]");
        jdbcTemplate.execute("ANALYZE variables");

        List<String> plan = jdbcTemplate.query(
            "EXPLAIN SELECT id FROM variables WHERE process_instance_id = '" + piId
                + "' AND scope_id IS NULL AND name IN ('items')",
            (rs, rowNum) -> rs.getString(1));
        assertThat(String.join("\n", plan))
            .as("pinpoint read plan uses the index, not a full scan")
            .doesNotContain("Seq Scan");

        ActivityEntity task = miTasks(piId, ActivityStatus.CREATED).get(0);
        List<String> scopedPlan = jdbcTemplate.query(
            "EXPLAIN SELECT id FROM variables WHERE process_instance_id = '" + piId
                + "' AND (scope_id IS NULL OR scope_id = '" + task.getId()
                + "') AND name IN ('loopCounter')",
            (rs, rowNum) -> rs.getString(1));
        assertThat(String.join("\n", scopedPlan))
            .as("scoped pinpoint read plan uses the index, not a full scan")
            .doesNotContain("Seq Scan");
    }
}
