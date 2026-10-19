package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.entity.OutboxKind;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.leak.LeakInvariantChecker;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-53 — scope leaks of MI {@code outputElement} and of output-mapping completion
 * variables (WO-OPS-30 F1 roots 5/6), measured against the live Zeebe 8.5.0 oracle.
 *
 * <p>Target semantics (live 8.5, {@code feel-iomapping-conformance-matrix.md} rows
 * #63/#65/#70/#71/#72):
 * <ul>
 *   <li>an MI instance's {@code outputElement} is <b>instance-local</b>: every job
 *       payload carries {@code r:null} (missing is not the same as null), nothing is
 *       propagated to the parent, and only {@code outputCollection} leaves the body;</li>
 *   <li>completion variables are visible to an element's output mappings but are
 *       <b>not</b> persisted in the parent scope — only mapped targets leave;</li>
 *   <li>output mappings are evaluated in declaration order and each target is visible
 *       to later mappings of the same element.</li>
 * </ul>
 *
 * <p>Each test drives the real engine path ({@code RuntimeService} → MI/service-task
 * handlers → outbox payload) and asserts the G-P leak invariant on the completed
 * instance through {@link LeakInvariantChecker} (no copied production logic — G-N).</p>
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class MiOutputScopeLeakIntegrationTests {

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
    private OutboxRepository outboxRepository;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ------------------------------------------------------------------ cases

    /**
     * F1 root 6 ({@code mi-basic}): parallel MI service task — every instance job sees
     * {@code r:null} (typed null, not an absent key), the raw value never reaches the
     * root, the positional outputCollection is the only propagated result.
     */
    @Transactional
    @Test
    void miParallel_outputElement_isInstanceLocal_andOnlyCollectionPropagates() throws Exception {
        Set<UUID> baseline = LeakInvariantChecker.snapshotCompletedInstanceIds(jdbcTemplate);
        UUID pi = start("test-c853-mi-service-output.bpmn", Map.of("items", List.of("a", "b")));

        List<ActivityEntity> jobs = live(pi, "miTask");
        assertThat(jobs).hasSize(2);
        List<Map<String, Object>> payloads = jobs.stream()
            .map(job -> normalized(payload(pi, job.getId())))
            .toList();
        assertThat(payloads).allSatisfy(payload -> assertThat(payload)
            .as("MI job payload must carry typed-null outputElement 'r'")
            .containsEntry("r", null));

        completeByItem(pi, jobs, "!");
        completeDump(pi);
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();

        Map<String, Object> root = normalizedRoot(pi);
        assertThat(root).as("raw outputElement must not leak to root").doesNotContainKey("r");
        assertThat(root.get("results")).isEqualTo(List.of("a!", "b!"));
        assertThat(allVariableNames(pi)).as("no scoped outputElement left behind")
            .doesNotContain("r");
        assertNoLeaks(pi, baseline);
    }

    /**
     * F1 root 6 ({@code mi-sequential}): the next instance must not see the previous
     * instance's {@code outputElement} — live 8.5 jobs 2 and 3 still carry {@code r:null}.
     */
    @Transactional
    @Test
    void miSequential_outputElement_isNotVisibleToNextInstance() throws Exception {
        Set<UUID> baseline = LeakInvariantChecker.snapshotCompletedInstanceIds(jdbcTemplate);
        UUID pi = start("test-c853-mi-service-output-sequential.bpmn",
            Map.of("items", List.of("a", "b", "c")));

        List<String> completedItems = new ArrayList<>();
        for (int round = 0; round < 3; round++) {
            List<ActivityEntity> jobs = live(pi, "miTask");
            assertThat(jobs).as("exactly one sequential instance at a time").hasSize(1);
            ActivityEntity job = jobs.get(0);
            Map<String, Object> payload = normalized(payload(pi, job.getId()));
            assertThat(payload).as("sequential instance %s payload", round + 1)
                .containsEntry("r", null);
            String item = String.valueOf(payload.get("item"));
            completedItems.add(item);
            runtimeService.completeServiceTask(job.getId(), variables("r", item + "!"));
            assertThat(live(pi, "miTask")).as("next instance spawned synchronously").hasSizeLessThanOrEqualTo(1);
        }
        assertThat(completedItems).containsExactly("a", "b", "c");
        completeDump(pi);
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();

        Map<String, Object> root = normalizedRoot(pi);
        assertThat(root).doesNotContainKey("r");
        assertThat(root.get("results")).isEqualTo(List.of("a!", "b!", "c!"));
        assertThat(allVariableNames(pi)).doesNotContain("r");
        assertNoLeaks(pi, baseline);
    }

    /**
     * F1 root 6 ({@code mi-empty-output}): a completion without the outputElement
     * variable still yields a typed-null key in the job payload and a null slot in the
     * collection (missing ≠ null).
     */
    @Transactional
    @Test
    void miEmptyOutput_jobsCarryNullOutputElement() throws Exception {
        Set<UUID> baseline = LeakInvariantChecker.snapshotCompletedInstanceIds(jdbcTemplate);
        UUID pi = start("test-c853-mi-service-output.bpmn", Map.of("items", List.of("a", "b")));

        List<ActivityEntity> jobs = live(pi, "miTask");
        assertThat(jobs).hasSize(2);
        for (ActivityEntity job : jobs) {
            assertThat(normalized(payload(pi, job.getId())))
                .as("job without a real outputElement value still carries r:null")
                .containsEntry("r", null);
            runtimeService.completeServiceTask(job.getId(), List.of());
        }
        completeDump(pi);
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();

        Map<String, Object> root = normalizedRoot(pi);
        assertThat(root).doesNotContainKey("r");
        assertThat(root.get("results")).isEqualTo(Arrays.asList(null, null));
        assertNoLeaks(pi, baseline);
    }

    /**
     * F1 root 5 ({@code output-completion}): the completion variable is visible to the
     * output mapping but is not merged into the parent scope — only the mapped target is.
     */
    @Transactional
    @Test
    void outputMapping_completionVariable_doesNotLeakToRoot() throws Exception {
        Set<UUID> baseline = LeakInvariantChecker.snapshotCompletedInstanceIds(jdbcTemplate);
        UUID pi = start("test-c853-output-completion.bpmn", Map.of());

        ActivityEntity work = single(pi, "work");
        assertThat(normalized(payload(pi, work.getId()))).isEmpty();
        runtimeService.completeServiceTask(work.getId(), variables("c", "C"));

        ActivityEntity dump = single(pi, "dump");
        assertThat(normalized(payload(pi, dump.getId())))
            .as("only the mapped target leaves the element")
            .isEqualTo(Map.of("o", "C"));
        runtimeService.completeServiceTask(dump.getId(), List.of());

        Map<String, Object> root = normalizedRoot(pi);
        assertThat(root).containsOnlyKeys("o");
        assertThat(root.get("o")).isEqualTo("C");
        assertNoLeaks(pi, baseline);
    }

    /**
     * F1 root 5 ({@code output-other-output}): output mappings are evaluated in order;
     * a later mapping sees the target produced by an earlier one ({@code =o1} → "a").
     */
    @Transactional
    @Test
    void outputMappings_areOrdered_targetVisibleToLaterMapping() throws Exception {
        Set<UUID> baseline = LeakInvariantChecker.snapshotCompletedInstanceIds(jdbcTemplate);
        UUID pi = start("test-c853-output-ordered.bpmn", Map.of());

        ActivityEntity work = single(pi, "work");
        runtimeService.completeServiceTask(work.getId(), List.of());

        ActivityEntity dump = single(pi, "dump");
        assertThat(normalized(payload(pi, dump.getId())))
            .isEqualTo(Map.of("o1", "a", "o2", "b", "o3", "a"));
        runtimeService.completeServiceTask(dump.getId(), List.of());

        assertThat(openIncidents(pi)).as("ordered output mappings must not raise an incident").isEmpty();
        assertNoLeaks(pi, baseline);
    }

    /**
     * MI body with output mappings: raw completion variables (outputElement and any
     * other name) stay instance-local; mapped targets leave; the outputCollection keeps
     * aggregating from the instance-local outputElement.
     */
    @Transactional
    @Test
    void miWithOutputMappings_onlyMappedTargetsLeaveTheBody() throws Exception {
        Set<UUID> baseline = LeakInvariantChecker.snapshotCompletedInstanceIds(jdbcTemplate);
        UUID pi = start("test-c853-mi-service-output-mapping.bpmn", Map.of("items", List.of("a", "b")));

        List<ActivityEntity> jobs = live(pi, "miTask");
        assertThat(jobs).hasSize(2);
        assertThat(jobs.stream().map(job -> String.valueOf(normalized(payload(pi, job.getId())).get("item")))
            .toList()).containsExactlyInAnyOrder("a", "b");

        completeByItem(pi, jobs, "!", Map.of("extra", "raw"));
        completeDump(pi);
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();

        Map<String, Object> root = normalizedRoot(pi);
        assertThat(root).doesNotContainKeys("r", "extra");
        assertThat(root.get("results")).isEqualTo(List.of("a!", "b!"));
        assertThat(root.get("out")).as("mapped target from the LAST completed instance")
            .isEqualTo("b!");
        assertNoLeaks(pi, baseline);
    }

    // ---------------------------------------------------------------- helpers

    private UUID start(String fixture, Map<String, Object> vars) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + fixture));
        ProcessDefinition definition = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(definition.getId());
        dto.setVariables(toVariables(vars));
        return runtimeService.startProcessInstance(dto).getId();
    }

    private void completeDump(UUID pi) {
        for (ActivityEntity dump : live(pi, "dump")) {
            runtimeService.completeServiceTask(dump.getId(), List.of());
        }
    }

    private ActivityEntity single(UUID pi, String elementId) {
        List<ActivityEntity> activities = live(pi, elementId);
        assertThat(activities).as("one live '%s' activity", elementId).hasSize(1);
        return activities.get(0);
    }

    private List<ActivityEntity> live(UUID pi, String elementId) {
        return activityRepository.findAll().stream()
            .filter(a -> pi.equals(a.getProcessInstanceId()))
            .filter(a -> elementId.equals(a.getBpmnElementId()))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED || a.getStatus() == ActivityStatus.IN_PROGRESS)
            .toList();
    }

    /** Completes every listed MI instance in item order with {@code r=<item>!} (+ optional extra vars). */
    private void completeByItem(UUID pi, List<ActivityEntity> jobs, String suffix, Map<String, Object> extra) {
        List<ActivityEntity> ordered = jobs.stream()
            .sorted(java.util.Comparator.comparing(
                job -> String.valueOf(normalized(payload(pi, job.getId())).get("item"))))
            .toList();
        for (ActivityEntity job : ordered) {
            String item = String.valueOf(normalized(payload(pi, job.getId())).get("item"));
            Map<String, Object> values = new LinkedHashMap<>(extra);
            values.put("r", item + suffix);
            runtimeService.completeServiceTask(job.getId(), toVariables(values));
        }
    }

    private void completeByItem(UUID pi, List<ActivityEntity> jobs, String suffix) {
        completeByItem(pi, jobs, suffix, Map.of());
    }

    /** The exact worker-visible job body written by ServiceTaskEnqueueServiceImpl. */
    private Map<String, Object> payload(UUID pi, UUID serviceTaskId) {
        for (OutboxEntry entry : outboxRepository.findAll()) {
            if (entry.getKind() != OutboxKind.SERVICE_TASK) {
                continue;
            }
            try {
                Map<String, Object> node = objectMapper.readValue(entry.getPayload(),
                    new TypeReference<Map<String, Object>>() { });
                if (!pi.toString().equals(String.valueOf(node.get("processInstanceId")))
                    || !serviceTaskId.toString().equals(String.valueOf(node.get("serviceTaskId")))) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> variables = (Map<String, Object>) node.get("variables");
                return variables;
            } catch (Exception e) {
                throw new IllegalStateException("unreadable outbox payload " + entry.getId(), e);
            }
        }
        throw new AssertionError("no service-task job payload for activity " + serviceTaskId);
    }

    /** Unwraps the typed {@code {type,value}} job payload into plain values. */
    private Map<String, Object> normalized(Map<String, Object> payload) {
        Map<String, Object> out = new LinkedHashMap<>();
        payload.forEach((name, entry) -> out.put(name, rawValue(entry)));
        return out;
    }

    private Object rawValue(Object entry) {
        if (!(entry instanceof Map<?, ?> wrapper) || !wrapper.containsKey("value")) {
            return entry;
        }
        Object value = wrapper.get("value");
        String type = String.valueOf(wrapper.get("type"));
        if (value == null) {
            return null;
        }
        return switch (type) {
            case "LONG" -> Long.parseLong(String.valueOf(value));
            case "DOUBLE" -> Double.parseDouble(String.valueOf(value));
            case "BOOLEAN" -> Boolean.parseBoolean(String.valueOf(value));
            case "JSON" -> readJson(String.valueOf(value));
            default -> String.valueOf(value);
        };
    }

    private Object readJson(String value) {
        try {
            return objectMapper.readValue(value, Object.class);
        } catch (Exception e) {
            throw new IllegalStateException("unreadable JSON variable " + value, e);
        }
    }

    private Map<String, Object> normalizedRoot(UUID pi) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (ProcessVariableEntity variable : variableRepository.findByProcessInstanceIdAndScopeIdIsNull(pi)) {
            out.put(variable.getName(), rootValue(variable));
        }
        return out;
    }

    private Object rootValue(ProcessVariableEntity variable) {
        if (variable.getTextValue() == null) {
            return null;
        }
        return switch (variable.getType()) {
            case LONG -> Long.parseLong(variable.getTextValue());
            case DOUBLE -> Double.parseDouble(variable.getTextValue());
            case BOOLEAN -> Boolean.parseBoolean(variable.getTextValue());
            case JSON -> readJson(variable.getTextValue());
            default -> variable.getTextValue();
        };
    }

    private List<String> allVariableNames(UUID pi) {
        return variableRepository.findByProcessInstanceId(pi).stream()
            .map(ProcessVariableEntity::getName)
            .toList();
    }

    private List<IncidentEntity> openIncidents(UUID pi) {
        List<UUID> activityIds = activityRepository.findAll().stream()
            .filter(a -> pi.equals(a.getProcessInstanceId()))
            .map(ActivityEntity::getId)
            .toList();
        if (activityIds.isEmpty()) {
            return List.of();
        }
        return incidentRepository.findByActivityIdInAndCompletedAtIsNull(activityIds);
    }

    private void assertNoLeaks(UUID pi, Set<UUID> baseline) {
        assertThat(openIncidents(pi)).as("no incidents on a successfully completed instance").isEmpty();
        List<LeakInvariantChecker.Violation> violations =
            LeakInvariantChecker.check(jdbcTemplate, baseline).stream()
                .filter(v -> pi.equals(v.instanceId()))
                .toList();
        assertThat(violations).as("G-P leak invariant for " + pi).isEmpty();
    }

    private List<ProcessVariable> variables(String name, String value) {
        return toVariables(Map.of(name, value));
    }

    private List<ProcessVariable> toVariables(Map<String, Object> vars) {
        List<ProcessVariable> result = new ArrayList<>();
        for (Map.Entry<String, Object> e : vars.entrySet()) {
            ProcessVariable variable = new ProcessVariable();
            variable.setName(e.getKey());
            Object value = e.getValue();
            if (value instanceof Boolean b) {
                variable.setType(ProcessVariableType.BOOLEAN);
                variable.setValue(b.toString());
            } else if (value instanceof Integer || value instanceof Long) {
                variable.setType(ProcessVariableType.LONG);
                variable.setValue(String.valueOf(value));
            } else if (value instanceof Number n) {
                variable.setType(ProcessVariableType.DOUBLE);
                variable.setValue(String.valueOf(n));
            } else if (value instanceof Map<?, ?> || value instanceof List<?>) {
                variable.setType(ProcessVariableType.JSON);
                variable.setValue(objectMapper.writeValueAsString(value));
            } else {
                variable.setType(ProcessVariableType.STRING);
                variable.setValue((String) value);
            }
            result.add(variable);
        }
        return result;
    }
}
