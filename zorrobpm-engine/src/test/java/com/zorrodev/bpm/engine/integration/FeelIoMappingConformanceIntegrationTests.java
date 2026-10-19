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
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-OPS-30 F1 — characterization measurements of FEEL / ioMapping behaviour on the
 * REAL engine path ({@code RuntimeService} → handlers → {@code ElementSupport} →
 * outbox job payload), against the case list measured on the live Zeebe 8.5.0 oracle
 * by {@code docs/analysis/c8-oracle/feel-iomapping.js}.
 *
 * <p>This class intentionally ASSERTS ONLY that the measurement itself happened
 * (a case produced an outcome). Divergences from Zeebe are collected in
 * {@code target/feel-iomapping-observed.json} and listed as bug-WO candidates in the
 * F1 report — fixing them is out of scope (WO-OPS-30 F1). G-N: every observation
 * goes through the production path; no inline copy of the mapping logic.</p>
 *
 * <p>Strict-missing is enabled by the test profile
 * ({@code zorrobpm.engine.io-mapping.strict-missing=true}), matching Zeebe's
 * incident-on-evaluation-error semantics. The production default (false, legacy
 * {@code ""}) is a documented WO-ENG-30 rollout state and is noted separately in the
 * matrix — it is not the parity target.</p>
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class FeelIoMappingConformanceIntegrationTests {

    private static final String CASES_RESOURCE = "feel-iomapping-cases.json";
    private static final String OBSERVED_PATH =
        System.getProperty("feel.observed.file", "target/feel-iomapping-observed.json");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Map<String, Object>> observed = Collections.synchronizedMap(new LinkedHashMap<>());

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    static Stream<Map<String, Object>> cases() throws Exception {
        try (InputStream in = FeelIoMappingConformanceIntegrationTests.class.getClassLoader()
                .getResourceAsStream(CASES_RESOURCE)) {
            assertThat(in).as("case list resource " + CASES_RESOURCE).isNotNull();
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> root = mapper.readValue(in, new TypeReference<Map<String, Object>>() { });
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cases = (List<Map<String, Object>>) root.get("cases");
            String filter = System.getProperty("feel.cases.filter", "");
            return cases.stream()
                .filter(c -> filter.isBlank() || String.valueOf(c.get("id")).startsWith(filter))
                .toList().stream();
        }
    }

    @Transactional
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void measure(Map<String, Object> c) throws Exception {
        String id = String.valueOf(c.get("id"));
        Map<String, Object> obs = new LinkedHashMap<>();
        obs.put("id", id);
        obs.put("title", c.get("title"));

        String processId = "f1_" + id.replaceAll("[^a-zA-Z0-9]", "_");
        ProcessDefinition definition;
        try {
            definition = processDefinitionService.addProcessDefinition(bpmnXml(c, processId));
        } catch (Exception e) {
            obs.put("outcome", "deployError");
            obs.put("error", rootMessage(e));
            observed.put(id, obs);
            return;
        }

        UUID pi;
        try {
            StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
            dto.setProcessDefinitionId(definition.getId());
            dto.setVariables(startVariables(c));
            pi = runtimeService.startProcessInstance(dto).getId();
        } catch (Exception e) {
            obs.put("outcome", "startError");
            obs.put("error", rootMessage(e));
            observed.put(id, obs);
            return;
        }
        obs.put("processInstanceId", pi.toString());

        String wrap = String.valueOf(c.getOrDefault("wrap", "root"));
        try {
            if ("mi".equals(wrap)) {
                measureMi(pi, c, obs);
            } else if ("subprocess".equals(wrap)) {
                measureSubprocess(pi, c, obs);
            } else {
                measureRoot(pi, c, obs);
            }
        } catch (Exception e) {
            obs.put("outcome", "error");
            obs.put("error", rootMessage(e));
        }
        obs.put("incidents", openIncidents(pi));
        observed.put(id, obs);
        assertThat(obs.get("outcome")).as("case %s produced a measurement", id).isNotNull();
    }

    private void measureRoot(UUID pi, Map<String, Object> c, Map<String, Object> obs) {
        ActivityEntity work = active(pi, "work").stream().findFirst().orElse(null);
        if (work == null) {
            obs.put("outcome", "noWorkJob");
            return;
        }
        Map<String, Object> payload = jobPayload(pi, work.getId());
        if (payload == null) {
            obs.put("outcome", "noWorkJob");
            obs.put("workActivity", work.getStatus().toString());
            return;
        }
        obs.put("work", payload);
        complete(work.getId(), c.get("completionVars"));

        ActivityEntity dump = active(pi, "dump").stream().findFirst().orElse(null);
        if (dump == null) {
            obs.put("outcome", "noDumpJob");
            return;
        }
        obs.put("dump", jobPayload(pi, dump.getId()));
        runtimeService.completeServiceTask(dump.getId(), List.of());
        obs.put("outcome", "completed");
    }

    private void measureSubprocess(UUID pi, Map<String, Object> c, Map<String, Object> obs) {
        ActivityEntity work = active(pi, "work").stream().findFirst().orElse(null);
        if (work == null) {
            obs.put("outcome", "noWorkJob");
            return;
        }
        Map<String, Object> payload = jobPayload(pi, work.getId());
        if (payload == null) {
            obs.put("outcome", "noWorkJob");
            obs.put("workActivity", work.getStatus().toString());
            return;
        }
        obs.put("work", payload);
        complete(work.getId(), c.get("completionVars"));

        ActivityEntity dumpIn = active(pi, "dumpIn").stream().findFirst().orElse(null);
        if (dumpIn == null) {
            obs.put("outcome", "noDumpInJob");
            return;
        }
        obs.put("dumpIn", jobPayload(pi, dumpIn.getId()));
        runtimeService.completeServiceTask(dumpIn.getId(), List.of());

        ActivityEntity rootDump = active(pi, "rootDump").stream().findFirst().orElse(null);
        if (rootDump == null) {
            obs.put("outcome", "noRootDumpJob");
            return;
        }
        obs.put("rootDump", jobPayload(pi, rootDump.getId()));
        runtimeService.completeServiceTask(rootDump.getId(), List.of());
        obs.put("outcome", "completed");
    }

    private void measureMi(UUID pi, Map<String, Object> c, Map<String, Object> obs) {
        List<Map<String, Object>> miJobs = new ArrayList<>();
        for (int round = 0; round < 20; round++) {
            List<ActivityEntity> works = active(pi, "miWork");
            if (!works.isEmpty()) {
                for (ActivityEntity work : works) {
                    Map<String, Object> payload = jobPayload(pi, work.getId());
                    if (payload == null) {
                        obs.put("outcome", "noMiJob");
                        obs.put("miJobs", miJobs);
                        return;
                    }
                    miJobs.add(payload);
                    complete(work.getId(), rendered(c.get("completionTemplate"), payload));
                }
                continue;
            }
            ActivityEntity dump = active(pi, "dump").stream().findFirst().orElse(null);
            if (dump != null) {
                obs.put("miJobs", miJobs);
                obs.put("dump", jobPayload(pi, dump.getId()));
                runtimeService.completeServiceTask(dump.getId(), List.of());
                obs.put("outcome", "completed");
                return;
            }
            break;
        }
        obs.put("miJobs", miJobs);
        obs.put("outcome", miJobs.isEmpty() ? "noMiJob" : "noDumpJob");
    }

    /**
     * Completion variables for an MI instance. The outbox payload carries typed
     * {@code {name,type,value}} entries, so a template placeholder must unwrap the
     * raw value — stringifying the wrapper would corrupt the measured output.
     */
    private Object rendered(Object template, Map<String, Object> payload) {
        if (!(template instanceof Map<?, ?>)) {
            return Map.of();
        }
        Map<String, Object> completion = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) template).entrySet()) {
            String value = String.valueOf(e.getValue());
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\$\\{([^}]+)}").matcher(value);
            StringBuilder rendered = new StringBuilder();
            while (matcher.find()) {
                Object replacement = rawValue(payload.get(matcher.group(1)));
                matcher.appendReplacement(rendered, java.util.regex.Matcher.quoteReplacement(
                    replacement == null ? matcher.group(0) : String.valueOf(replacement)));
            }
            matcher.appendTail(rendered);
            completion.put(String.valueOf(e.getKey()), rendered.toString());
        }
        return completion;
    }

    private static Object rawValue(Object payloadEntry) {
        if (payloadEntry instanceof Map<?, ?> m && m.containsKey("value")) {
            return m.get("value");
        }
        return payloadEntry;
    }

    private void complete(UUID activityId, Object completionVars) {
        List<ProcessVariable> vars = toVariables(completionVars instanceof Map<?, ?> m
            ? castMap(m) : Map.of());
        runtimeService.completeServiceTask(activityId, vars);
    }

    private List<ActivityEntity> active(UUID pi, String elementId) {
        return activityRepository.findAll().stream()
            .filter(a -> pi.equals(a.getProcessInstanceId()))
            .filter(a -> elementId.equals(a.getBpmnElementId()))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED || a.getStatus() == ActivityStatus.IN_PROGRESS)
            .toList();
    }

    /** The exact worker-visible job body written by ServiceTaskEnqueueServiceImpl. */
    private Map<String, Object> jobPayload(UUID pi, UUID serviceTaskId) {
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
        return null;
    }

    private List<Map<String, Object>> openIncidents(UUID pi) {
        List<UUID> activityIds = activityRepository.findAll().stream()
            .filter(a -> pi.equals(a.getProcessInstanceId()))
            .map(ActivityEntity::getId)
            .toList();
        if (activityIds.isEmpty()) {
            return List.of();
        }
        return incidentRepository.findByActivityIdInAndCompletedAtIsNull(activityIds).stream()
            .map(i -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("activityId", i.getActivityId());
                m.put("message", i.getMessage());
                return m;
            })
            .toList();
    }

    private List<ProcessVariable> startVariables(Map<String, Object> c) {
        Object vars = c.get("vars");
        return toVariables(vars instanceof Map<?, ?> m ? castMap(m) : Map.of());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Map<?, ?> m) {
        Map<String, Object> result = new LinkedHashMap<>();
        m.forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }

    private List<ProcessVariable> toVariables(Map<String, Object> vars) {
        List<ProcessVariable> result = new ArrayList<>();
        for (Map.Entry<String, Object> e : vars.entrySet()) {
            ProcessVariable v = new ProcessVariable();
            v.setName(e.getKey());
            Object value = e.getValue();
            if (value instanceof Boolean b) {
                v.setType(ProcessVariableType.BOOLEAN);
                v.setValue(b.toString());
            } else if (value instanceof Integer || value instanceof Long) {
                v.setType(ProcessVariableType.LONG);
                v.setValue(String.valueOf(value));
            } else if (value instanceof Number n) {
                v.setType(ProcessVariableType.DOUBLE);
                v.setValue(n instanceof BigDecimal bd ? bd.toPlainString() : String.valueOf(n));
            } else if (value instanceof Map<?, ?> || value instanceof List<?>) {
                v.setType(ProcessVariableType.JSON);
                v.setValue(objectMapper.writeValueAsString(value));
            } else {
                v.setType(ProcessVariableType.STRING);
                v.setValue((String) value);
            }
            result.add(v);
        }
        return result;
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    private static String esc(String s) {
        return String.valueOf(s)
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }

    private static String ioMapping(Object inputs, Object outputs) {
        StringBuilder sb = new StringBuilder();
        for (Map<?, ?> m : mappings(inputs)) {
            sb.append("<zeebe:input source=\"").append(esc(String.valueOf(m.get("source"))))
                .append("\" target=\"").append(esc(String.valueOf(m.get("target")))).append("\"/>");
        }
        for (Map<?, ?> m : mappings(outputs)) {
            sb.append("<zeebe:output source=\"").append(esc(String.valueOf(m.get("source"))))
                .append("\" target=\"").append(esc(String.valueOf(m.get("target")))).append("\"/>");
        }
        return sb.isEmpty() ? "" : "<zeebe:ioMapping>" + sb + "</zeebe:ioMapping>";
    }

    private static List<Map<?, ?>> mappings(Object o) {
        if (!(o instanceof List<?> list)) {
            return List.of();
        }
        List<Map<?, ?>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                result.add(m);
            }
        }
        return result;
    }

    private static String extensions(String taskType, Object inputs, Object outputs) {
        StringBuilder sb = new StringBuilder();
        if (taskType != null) {
            sb.append("<zeebe:taskDefinition type=\"").append(esc(taskType)).append("\"/>");
        }
        sb.append(ioMapping(inputs, outputs));
        return sb.isEmpty() ? "" : "<bpmn:extensionElements>" + sb + "</bpmn:extensionElements>";
    }

    private static String serviceTask(String id, String type, String incoming, String outgoing,
            Object inputs, Object outputs, String extra) {
        return "<bpmn:serviceTask id=\"" + id + "\" name=\"" + id + "\">"
            + extensions(type, inputs, outputs)
            + "<bpmn:incoming>" + incoming + "</bpmn:incoming>"
            + "<bpmn:outgoing>" + outgoing + "</bpmn:outgoing>"
            + (extra == null ? "" : extra)
            + "</bpmn:serviceTask>";
    }

    private static String loopCharacteristics(Map<String, Object> c) {
        return "<bpmn:multiInstanceLoopCharacteristics isSequential=\""
            + (Boolean.TRUE.equals(c.get("isSequential")) ? "true" : "false") + "\">"
            + "<bpmn:extensionElements><zeebe:loopCharacteristics "
            + "inputCollection=\"=items\" inputElement=\"item\" "
            + "outputCollection=\"" + esc(String.valueOf(c.getOrDefault("outputCollection", "results"))) + "\" "
            + "outputElement=\"" + esc(String.valueOf(c.getOrDefault("outputElement", "=r"))) + "\"/>"
            + "</bpmn:extensionElements></bpmn:multiInstanceLoopCharacteristics>";
    }

    private static String bpmnXml(Map<String, Object> c, String pid) {
        String wrap = String.valueOf(c.getOrDefault("wrap", "root"));
        String body;
        if ("subprocess".equals(wrap)) {
            String inner = "<bpmn:startEvent id=\"spStart\"><bpmn:outgoing>s1</bpmn:outgoing></bpmn:startEvent>"
                + serviceTask("work", "feelWork", "s1", "s2", c.get("input"), c.get("output"), null)
                + serviceTask("dumpIn", "feelDumpIn", "s2", "s3", null, null, null)
                + "<bpmn:endEvent id=\"spEnd\"><bpmn:incoming>s3</bpmn:incoming></bpmn:endEvent>"
                + "<bpmn:sequenceFlow id=\"s1\" sourceRef=\"spStart\" targetRef=\"work\"/>"
                + "<bpmn:sequenceFlow id=\"s2\" sourceRef=\"work\" targetRef=\"dumpIn\"/>"
                + "<bpmn:sequenceFlow id=\"s3\" sourceRef=\"dumpIn\" targetRef=\"spEnd\"/>";
            body = "<bpmn:startEvent id=\"start\"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>"
                + "<bpmn:subProcess id=\"sp\" name=\"sp\">"
                + extensions(null, c.get("containerInput"), c.get("containerOutput"))
                + "<bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>"
                + inner + "</bpmn:subProcess>"
                + serviceTask("rootDump", "feelDump", "f2", "f3", null, null, null)
                + "<bpmn:endEvent id=\"end\"><bpmn:incoming>f3</bpmn:incoming></bpmn:endEvent>"
                + "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"sp\"/>"
                + "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"sp\" targetRef=\"rootDump\"/>"
                + "<bpmn:sequenceFlow id=\"f3\" sourceRef=\"rootDump\" targetRef=\"end\"/>";
        } else if ("mi".equals(wrap)) {
            body = "<bpmn:startEvent id=\"start\"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>"
                + serviceTask("miWork", "feelMiWork", "f1", "f2", null, null, loopCharacteristics(c))
                + serviceTask("dump", "feelDump", "f2", "f3", null, null, null)
                + "<bpmn:endEvent id=\"end\"><bpmn:incoming>f3</bpmn:incoming></bpmn:endEvent>"
                + "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"miWork\"/>"
                + "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"miWork\" targetRef=\"dump\"/>"
                + "<bpmn:sequenceFlow id=\"f3\" sourceRef=\"dump\" targetRef=\"end\"/>";
        } else {
            body = "<bpmn:startEvent id=\"start\"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>"
                + serviceTask("work", "feelWork", "f1", "f2", c.get("input"), c.get("output"), null)
                + serviceTask("dump", "feelDump", "f2", "f3", null, null, null)
                + "<bpmn:endEvent id=\"end\"><bpmn:incoming>f3</bpmn:incoming></bpmn:endEvent>"
                + "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"work\"/>"
                + "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"work\" targetRef=\"dump\"/>"
                + "<bpmn:sequenceFlow id=\"f3\" sourceRef=\"dump\" targetRef=\"end\"/>";
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
            + "xmlns:zeebe=\"http://camunda.org/schema/zeebe/1.0\" "
            + "id=\"Definitions_" + pid + "\" targetNamespace=\"http://bpmn.io/schema/bpmn\">"
            + "<bpmn:process id=\"" + pid + "\" name=\"" + pid + "\" isExecutable=\"true\">"
            + body + "</bpmn:process></bpmn:definitions>";
    }

    @AfterAll
    void writeObserved() throws Exception {
        Path path = Path.of(OBSERVED_PATH);
        Files.createDirectories(path.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), observed);
    }
}
