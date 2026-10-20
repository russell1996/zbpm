package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.exception.ApiException;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.entity.OutboxKind;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-C8-55 (F1 campaign, root 3 «source без «=»»): an ioMapping source WITHOUT a leading
 * {@code =} is a STATIC VALUE, not a FEEL expression — exactly as live Zeebe 8.5 treats it
 * (measured on the live oracle, {@code docs/analysis/c8-oracle/feel-iomapping-static-nested.js}:
 * source {@code hello} → the string {@code "hello"}; source {@code present} → the string
 * {@code "present"} even when a variable of that name exists; source {@code 42} → the string
 * {@code "42"}; source {@code {"a":1}} → the string {@code "{\"a\":1}"}; source {@code "  hello  "}
 * → the string with its spaces kept).
 *
 * <p>Before this WO the engine evaluated such a source as FEEL: a bare word became a variable
 * reference (resolved, or a null value / incident), a number became a number, a JSON text was
 * refused at deploy as unparseable FEEL. All of that is the «ours differs» row 57/58 of the
 * conformance matrix.</p>
 *
 * <p>The mirror rule on the OUTPUT side is a DEPLOY refusal: live 8.5 answers
 * «Expected expression but found static value 'hello'. An expression must start with '='»
 * (measured live, same oracle) — an output source is an EXPRESSION, never a static value.</p>
 *
 * <p>V11 / G-N: every case goes through the PRODUCTION path — {@code ProcessDefinitionService
 * .addProcessDefinition} (the deploy gate the REST endpoint, the batch deployer and the
 * submission-approval flow all funnel into) and {@code RuntimeService.startProcessInstance} →
 * the handlers → {@code ElementSupport} → the OUTBOX job payload (the worker-visible body, the
 * same artifact the live oracle measured). No copy of the mapping logic lives in this test.</p>
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
@Transactional
public class IoMappingStaticSourceIntegrationTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private ProcessDefinitionRepository processDefinitionRepository;

    /**
     * The static-value rule over the spellings the live oracle measured (G-Q: the expected
     * values are the live-8.5 ones, not ours). Every row is a bare word / number / JSON text —
     * NONE of them is a FEEL expression, and none of them may be evaluated as one.
     */
    @ParameterizedTest(name = "source ''{0}'' → the literal {1}")
    @MethodSource("staticSources")
    void inputSourceWithoutEquals_isDeliveredAsTheLiteralString(String source, String expected) {
        String key = uniqueKey("c855Static");
        ProcessDefinition definition = processDefinitionService.addProcessDefinition(
            bpmn(key, "<zeebe:input source=\"" + escape(source) + "\" target=\"r\"/>"));

        Started started = start(definition, Map.of());
        Map<String, Object> job = jobPayload(started.pi(), started.work().getId());

        assertThat(job).as("job payload of source '%s'", source).containsEntry("r", expected);
        assertThat(job.get("r")).as("the value of source '%s' is a STRING", source)
            .isInstanceOf(String.class);
        completeAndAssertNoLeftovers(started);
    }

    /**
     * The rule that makes the difference visible: a bare word that IS a variable name is still a
     * static value — live 8.5 delivers the TEXT, not the variable's value (row 58 of the matrix).
     */
    @Test
    void inputSourceWithoutEquals_ignoresAVariableOfTheSameName() {
        String key = uniqueKey("c855Shadow");
        ProcessDefinition definition = processDefinitionService.addProcessDefinition(
            bpmn(key, "<zeebe:input source=\"present\" target=\"r\"/>"));

        Started started = start(definition, Map.of("present", "V"));
        Map<String, Object> job = jobPayload(started.pi(), started.work().getId());

        assertThat(job).as("the literal wins over the variable of the same name")
            .containsEntry("r", "present")
            .containsEntry("present", "V");
        completeAndAssertNoLeftovers(started);
    }

    /**
     * The regression guard: a source WITH {@code =} is still a FEEL expression and is still
     * evaluated as one. Without this case a too-broad «static value» rule would pass the
     * literal cases above while breaking every real model.
     */
    @Test
    void inputSourceWithEquals_isStillEvaluatedAsFeel() {
        String key = uniqueKey("c855Feel");
        ProcessDefinition definition = processDefinitionService.addProcessDefinition(
            bpmn(key, "<zeebe:input source=\"=1 + 1\" target=\"r\"/>"));

        Started started = start(definition, Map.of());
        Map<String, Object> job = jobPayload(started.pi(), started.work().getId());

        assertThat(job).as("'=1 + 1' is a FEEL expression, not a literal")
            .containsEntry("r", 2L);
        completeAndAssertNoLeftovers(started);
    }

    /**
     * A static value that is not even parseable as FEEL (a JSON text) must DEPLOY — live 8.5
     * accepts it as a static value; the engine used to refuse it at deploy as broken FEEL.
     */
    @Test
    void inputSourceThatIsNotFeel_stillDeploys() {
        String key = uniqueKey("c855NotFeel");
        ProcessDefinition definition = processDefinitionService.addProcessDefinition(
            bpmn(key, "<zeebe:input source=\"{&quot;a&quot;:1}\" target=\"r\"/>"));

        assertThat(definition).as("a static value is not a FEEL expression — it must deploy")
            .isNotNull();
        assertThat(processDefinitionRepository.findById(definition.getId())).isPresent();
    }

    /**
     * The output side: live 8.5 refuses the model at DEPLOY («Expected expression but found static
     * value 'hello'. An expression must start with '='»). The refusal is total — no version row,
     * no registry row — and the answer names the element and the field.
     */
    @Test
    void outputSourceWithoutEquals_isRejectedAtDeploy() {
        String key = uniqueKey("c855OutStatic");

        assertThatThrownBy(() -> processDefinitionService.addProcessDefinition(
            bpmn(key, "<zeebe:output source=\"hello\" target=\"r\"/>")))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> {
                ApiException api = (ApiException) ex;
                assertThat(api.getStatus()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
                assertThat(api.getCode()).isEqualTo("INVALID_FEEL_EXPRESSION");
                assertThat(api.getParams().get("elementIds")).asList().containsExactly("work");
                assertThat(api.getMessage())
                    .contains("work")
                    .contains("ioMapping.output.source")
                    .contains("Expected expression but found static value 'hello'")
                    .contains("An expression must start with '='");
            });

        assertThat(processDefinitionRepository.findAll())
            .as("a refused model leaves no version row")
            .noneMatch(pd -> pd.getKey().startsWith(key));
    }

    /**
     * The regression guard for the refusal: an output source WITH {@code =} keeps deploying
     * (the refusal must not eat working models).
     */
    @Test
    void outputSourceWithEquals_stillDeploys() {
        String key = uniqueKey("c855OutFeel");
        ProcessDefinition definition = processDefinitionService.addProcessDefinition(
            bpmn(key, "<zeebe:output source=\"=1\" target=\"r\"/>"));

        assertThat(definition).isNotNull();
        assertThat(processDefinitionRepository.findById(definition.getId())).isPresent();
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    /**
     * The spellings the live oracle measured (G-Q: the expected values are the live-8.5 ones,
     * not ours). A bare word / number / JSON text / value with spaces — NONE of them is a FEEL
     * expression, and none of them may be evaluated as one.
     */
    static Stream<Arguments> staticSources() {
        return Stream.of(
            Arguments.of("hello", "hello"),
            Arguments.of("PATCH", "PATCH"),
            Arguments.of("42", "42"),
            Arguments.of("hello world", "hello world"),
            Arguments.of("{\"a\":1}", "{\"a\":1}"),
            Arguments.of("  hello  ", "  hello  "));
    }

    /** A started instance: its id and the service task that is waiting for a worker. */
    private record Started(UUID pi, ActivityEntity work) {
    }

    private Started start(ProcessDefinition definition, Map<String, String> startVars) {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(definition.getId());
        List<ProcessVariable> variables = new ArrayList<>();
        startVars.forEach((name, value) -> {
            ProcessVariable v = new ProcessVariable();
            v.setName(name);
            v.setType(ProcessVariableType.STRING);
            v.setValue(value);
            variables.add(v);
        });
        dto.setVariables(variables);
        UUID pi = runtimeService.startProcessInstance(dto).getId();

        ActivityEntity work = activityRepository.findAll().stream()
            .filter(a -> pi.equals(a.getProcessInstanceId()))
            .filter(a -> "work".equals(a.getBpmnElementId()))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED || a.getStatus() == ActivityStatus.IN_PROGRESS)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no work activity for " + pi));
        return new Started(pi, work);
    }

    /**
     * WO-C8-55 / G-P: finishes the instance and proves that NOTHING of it is left alive —
     * no activity in CREATED/IN_PROGRESS, no unfinished task. The global
     * {@code LeakInvariantExtension} (WO-OPS-26) checks the rest of the invariant
     * (tokens, timer jobs, subscriptions, arrival rows, scope variables) for every
     * instance completed during the test — the runs of this class are made with
     * {@code -Dzbpm.leakInvariant=enforce}, so a violation FAILS the test instead of
     * being written to a report file.
     */
    private void completeAndAssertNoLeftovers(Started started) {
        runtimeService.completeServiceTask(started.work().getId(), List.of());

        List<ActivityEntity> alive = activityRepository.findAll().stream()
            .filter(a -> started.pi().equals(a.getProcessInstanceId()))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED || a.getStatus() == ActivityStatus.IN_PROGRESS)
            .toList();
        assertThat(alive)
            .as("no activity of a finished instance may stay alive (G-P leak invariant)")
            .isEmpty();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jobPayload(UUID pi, UUID serviceTaskId) {
        for (OutboxEntry entry : outboxRepository.findAll()) {
            if (entry.getKind() != OutboxKind.SERVICE_TASK) {
                continue;
            }
            Map<String, Object> node;
            try {
                node = objectMapper.readValue(entry.getPayload(), new TypeReference<Map<String, Object>>() { });
            } catch (Exception e) {
                throw new IllegalStateException("unreadable outbox payload " + entry.getId(), e);
            }
            if (!pi.toString().equals(String.valueOf(node.get("processInstanceId")))
                || !serviceTaskId.toString().equals(String.valueOf(node.get("serviceTaskId")))) {
                continue;
            }
            Map<String, Object> variables = (Map<String, Object>) node.get("variables");
            Map<String, Object> unwrapped = new LinkedHashMap<>();
            variables.forEach((name, entry2) -> unwrapped.put(name, unwrap(entry2)));
            return unwrapped;
        }
        return null;
    }

    /** The typed {@code {name,type,value}} wrapper → the plain value the worker sees. */
    private Object unwrap(Object entry) {
        if (!(entry instanceof Map<?, ?> wrapper) || !wrapper.containsKey("value")) {
            return entry;
        }
        Object value = wrapper.get("value");
        if (value == null) {
            return null;
        }
        String type = String.valueOf(wrapper.get("type"));
        try {
            return switch (type) {
                case "LONG" -> Long.parseLong(String.valueOf(value));
                case "DOUBLE" -> Double.parseDouble(String.valueOf(value));
                case "BOOLEAN" -> Boolean.parseBoolean(String.valueOf(value));
                case "JSON" -> objectMapper.readValue(String.valueOf(value), Object.class);
                default -> String.valueOf(value);
            };
        } catch (Exception e) {
            throw new IllegalStateException("unreadable variable wrapper " + entry, e);
        }
    }

    private static String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }

    /** A one-service-task model: start → work (with the given ioMapping) → end. */
    private static String bpmn(String key, String ioMapping) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
            + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
            + "xmlns:zeebe=\"http://camunda.org/schema/zeebe/1.0\" "
            + "id=\"Definitions_" + key + "\" targetNamespace=\"http://bpmn.io/schema/bpmn\">"
            + "<bpmn:process id=\"" + key + "\" name=\"" + key + "\" isExecutable=\"true\">"
            + "<bpmn:startEvent id=\"start\"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>"
            + "<bpmn:serviceTask id=\"work\" name=\"work\">"
            + "<bpmn:extensionElements>"
            + "<zeebe:taskDefinition type=\"c855Work\"/>"
            + "<zeebe:ioMapping>" + ioMapping + "</zeebe:ioMapping>"
            + "</bpmn:extensionElements>"
            + "<bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>"
            + "</bpmn:serviceTask>"
            + "<bpmn:endEvent id=\"end\"><bpmn:incoming>f2</bpmn:incoming></bpmn:endEvent>"
            + "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"work\"/>"
            + "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"work\" targetRef=\"end\"/>"
            + "</bpmn:process></bpmn:definitions>";
    }
}
