package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.entity.OutboxKind;
import com.zorrodev.bpm.engine.entity.ServiceTaskEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import com.zorrodev.bpm.engine.scheduler.StuckServiceTaskBatchProcessor;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-OPS-30 F2a — измерение ПРОТОКОЛА JOB на нашем движке (колонка «ours»
 * матрицы {@code docs/analysis/job-protocol-conformance-matrix.md}).
 *
 * <p>Тот же список кейсов, что и на живом Zeebe 8.5
 * ({@code zorrobpm-engine/src/test/resources/job-protocol-cases.json}) — это
 * ЕДИНСТВЕННЫЙ источник правды: модель BPMN и сами шаги. Оракул
 * ({@code docs/analysis/c8-oracle/job-protocol.js}) исполняет те же шаги против
 * живого брокера и кладёт рядом свои наблюдения.
 *
 * <p>G-N: каждое наблюдение снято с ЖИВОГО пути движка — {@code RuntimeService} /
 * {@code ActivityService} → handler'ы → outbox-тело задания (то, что реально
 * видит воркер) → таблицы {@code service_tasks} / {@code incidents}. Копии
 * логики движка в тесте нет.
 *
 * <p>Ожидаемых значений этот класс НЕ придумывает: он меряет и пишет
 * {@code target/job-protocol-observed.json}; сверка с живым 8.5 — в матрице
 * (G-Q). Ассерты — только на то, что измерение состоялось.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class JobProtocolConformanceSupport {

    static final String CASES_RESOURCE = "job-protocol-cases.json";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Map<String, Object>> observed = Collections.synchronizedMap(new LinkedHashMap<>());

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private ActivityService activityService;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private ServiceTaskRepository serviceTaskRepository;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private StuckServiceTaskBatchProcessor stuckServiceTaskBatchProcessor;

    /** Файл наблюдений этого прогона (две колонки матрицы — ours). */
    protected String observedFile() {
        return System.getProperty("job.protocol.observed.file", "target/job-protocol-observed.json");
    }

    Stream<Map<String, Object>> cases() throws Exception {
        try (InputStream in = JobProtocolConformanceSupport.class.getClassLoader()
                .getResourceAsStream(CASES_RESOURCE)) {
            assertThat(in).as("case list resource " + CASES_RESOURCE).isNotNull();
            Map<String, Object> root = new ObjectMapper().readValue(in, new TypeReference<Map<String, Object>>() { });
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cases = (List<Map<String, Object>>) root.get("cases");
            String filter = System.getProperty("job.protocol.cases.filter", "");
            return cases.stream()
                .filter(c -> filter.isBlank() || String.valueOf(c.get("id")).startsWith(filter))
                .toList().stream();
        }
    }

    /**
     * Измерение одного кейса. Точка входа {@code @ParameterizedTest} живёт в
     * конкретном классе (имя метода должно быть видно в НЁМ классе — гейт G4b
     * сверяет {@code Class.method} с исходником класса), а делегирует сюда.
     */
    protected void measureCase(Map<String, Object> c) throws Exception {
        String id = String.valueOf(c.get("id"));
        Map<String, Object> obs = new LinkedHashMap<>();
        obs.put("id", id);
        obs.put("title", c.get("title"));

        String processId = String.valueOf(c.get("processId"));
        ProcessDefinition definition;
        String xml;
        try {
            xml = bpmnXml(c);
            definition = processDefinitionService.addProcessDefinition(xml);
        } catch (Exception e) {
            obs.put("outcome", "deployError");
            obs.put("error", rootMessage(e));
            observed.put(id, obs);
            return;
        }
        // объявленный тип задания (из zeebe:taskDefinition) — тот же факт, что
        // на live-8.5 виден в поле jobType даже когда задания ещё нет
        Map<String, String> declaredTypes = declaredJobTypes(xml);

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

        List<Map<String, Object>> observations = new ArrayList<>();
        try {
            for (Map<String, Object> step : steps(c)) {
                observations.add(runStep(pi, step, declaredTypes));
            }
        } catch (Exception e) {
            obs.put("outcome", "error");
            obs.put("error", rootMessage(e));
        }
        obs.put("observations", observations);
        obs.put("incidents", openIncidents(pi));
        // G-P: инвариант утечек — после сценария не остаётся «мусорных» активностей
        // (каждая элемент либо завершён, либо отменён, либо ошибён, либо живая по
        // сценарию) и инцидентов ровно столько, сколько ожидает кейс.
        obs.put("finalActivities", finalActivities(pi));
        obs.put("openIncidentCount", openIncidents(pi).size());
        observed.put(id, obs);
        assertThat(obs.get("observations")).as("case %s produced observations", id).isNotNull();
        assertExpectations(c, observations);
        assertLeakInvariant(c, pi);
    }

    /** G-P (LEAK-INVARIANT): финальные состояния активностей и число открытых инцидентов. */
    private void assertLeakInvariant(Map<String, Object> c, UUID pi) {
        Object raw = c.get("expect");
        if (!(raw instanceof Map<?, ?> expect)) {
            return;
        }
        Object leak = expect.get("leak");
        if (!(leak instanceof Map<?, ?> leakMap)) {
            return;
        }
        String id = String.valueOf(c.get("id"));
        Object wantActivities = leakMap.get("activities");
        if (wantActivities instanceof Map<?, ?> wanted) {
            // СРАВНИВАЕМ ПОЛНЫЙ набор активностей инстанса, а не только объявленные:
            // иначе «забытая» в карте ожиданий живая (CREATED) активность осталась бы
            // незамеченной (находка reviewer Б-3: убрали "dump" из карты — тест был зелёным).
            Map<String, String> expected = new LinkedHashMap<>();
            wanted.forEach((k, v) -> expected.put(String.valueOf(k), String.valueOf(v)));
            assertThat(finalActivities(pi)).as("final activities in case %s", id)
                .isEqualTo(expected);
        }
        Object wantIncidents = leakMap.get("incidents");
        if (wantIncidents != null) {
            assertThat(openIncidents(pi).size())
                .as("open incidents in case %s", id)
                .isEqualTo(Integer.parseInt(String.valueOf(wantIncidents)));
        }
    }

    /** Финальное состояние каждой активности инстанса (element id → статус). */
    private Map<String, String> finalActivities(UUID pi) {
        Map<String, String> out = new LinkedHashMap<>();
        for (ActivityEntity a : activityRepository.findAll()) {
            if (pi.equals(a.getProcessInstanceId())) {
                out.put(a.getBpmnElementId(), a.getStatus().toString());
            }
        }
        return out;
    }

    /**
     * Ожидания кейса. Для строк, где мы СООТВЕТСТВУЕМ живому 8.5, ожидание —
     * это ИЗМЕРЕННЫЕ на живом 8.5 значения (G-Q: источник ожиданий — оракул, не
     * наша реализация). Для строк с известным расхождением ожидание фиксирует
     * НАШЕ измеренное поведение (характеризация расхождения), а измеренное
     * значение живого 8.5 лежит рядом в {@code liveReference} — его видно в
     * матрице и в отчёте.
     */
    void assertExpectations(Map<String, Object> c, List<Map<String, Object>> observations) {
        Object raw = c.get("expect");
        if (!(raw instanceof Map<?, ?> expect)) {
            return;
        }
        Object list = expect.get("observations");
        if (!(list instanceof List<?> expected)) {
            return;
        }
        String id = String.valueOf(c.get("id"));
        for (Object item : expected) {
            @SuppressWarnings("unchecked")
            Map<String, Object> want = (Map<String, Object>) item;
            Map<String, Object> got = findObservation(observations, want);
            assertThat(got).as("observation %s of case %s", want.get("step"), id).isNotNull();
            assertField(id, want, got, "outcome");
            assertField(id, want, got, "jobType");
            assertField(id, want, got, "retries");
            assertField(id, want, got, "workerVisibleRetries");
            assertField(id, want, got, "handled");
            if (want.containsKey("variables")) {
                assertThat(parseVars(got.get("variables"))).as("variables of %s in case %s", want.get("step"), id)
                    .isEqualTo(parseVars(want.get("variables")));
            }
            if (want.containsKey("taskHeaders")) {
                assertThat(asMap(got.get("taskHeaders"))).as("taskHeaders of %s in case %s", want.get("step"), id)
                    .isEqualTo(asMap(want.get("taskHeaders")));
            }
            if (want.containsKey("messageContains")) {
                List<Map<String, Object>> incidents = asList(got.get("incidents"));
                assertThat(incidents).as("incidents of %s in case %s", want.get("step"), id).isNotEmpty();
                assertThat(String.valueOf(incidents.get(0).get("message")))
                    .as("incident message of %s in case %s", want.get("step"), id)
                    .contains(String.valueOf(want.get("messageContains")));
            }
        }
    }

    private void assertField(String id, Map<String, Object> want, Map<String, Object> got, String field) {
        if (!want.containsKey(field)) {
            return;
        }
        Object expected = want.get(field);
        Object actual = got.get(field);
        if (expected instanceof Number n && actual instanceof Number a) {
            assertThat(a.longValue()).as("%s of case %s", field, id).isEqualTo(n.longValue());
            return;
        }
        assertThat(actual).as("%s of case %s", field, id).isEqualTo(expected);
    }

    /** Наблюдение по (шаг, элемент) с учётом порядкового номера повторов. */
    private Map<String, Object> findObservation(List<Map<String, Object>> observations, Map<String, Object> want) {
        int occurrence = want.get("occurrence") == null ? 1 : Integer.parseInt(String.valueOf(want.get("occurrence")));
        int seen = 0;
        for (Map<String, Object> o : observations) {
            if (!String.valueOf(want.get("step")).equals(String.valueOf(o.get("step")))) {
                continue;
            }
            String wantElement = want.get("element") == null ? null : String.valueOf(want.get("element"));
            String gotElement = o.get("element") == null ? null : String.valueOf(o.get("element"));
            if (wantElement != null && !wantElement.equals(gotElement)) {
                continue;
            }
            seen++;
            if (seen == occurrence) {
                return o;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                Map<String, Object> e = new LinkedHashMap<>();
                m.forEach((k, v) -> e.put(String.valueOf(k), v));
                out.add(e);
            }
        }
        return out;
    }

    /** Переменные наблюдения (JSON-строка) → карта для сравнения по содержанию. */
    private Map<String, Object> parseVars(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (raw instanceof Map<?, ?> m) {
            return asMap(m);
        }
        try {
            return asMap(objectMapper.readValue(String.valueOf(raw), new TypeReference<Map<String, Object>>() { }));
        } catch (Exception e) {
            throw new IllegalStateException("unreadable variables " + raw, e);
        }
    }

    private List<Map<String, Object>> steps(Map<String, Object> c) {
        Object raw = c.get("steps");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Object o : list) {
            @SuppressWarnings("unchecked")
            Map<String, Object> step = (Map<String, Object>) o;
            steps.add(step);
        }
        return steps;
    }

    /** Один шаг сценария — те же действия, что выполняет оракул. */
    private Map<String, Object> runStep(UUID pi, Map<String, Object> step, Map<String, String> declaredTypes) {
        String action = String.valueOf(step.get("action"));
        String element = step.get("element") == null ? null : String.valueOf(step.get("element"));
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("step", action);
        o.put("element", element);
        try {
            switch (action) {
                case "observeJob", "observeJobImmediately" -> {
                    // объявленный тип задания — тот же факт, что на live-8.5 виден
                    // в поле jobType (даже когда задания ещё нет)
                    String declaredType = declaredTypes.get(element);
                    ActivityEntity activity = active(pi, element);
                    if (activity == null) {
                        o.put("jobType", declaredType);
                        o.put("outcome", "noJob");
                        return o;
                    }
                    Map<String, Object> payload = jobPayload(pi, activity.getId());
                    if (payload == null) {
                        o.put("jobType", declaredType);
                        o.put("outcome", "noJob");
                        o.put("workActivity", activity.getStatus().toString());
                        return o;
                    }
                    o.put("jobType", payload.get("job"));
                    o.put("retries", remainingRetries(activity.getId()));
                    // таймаут активации — только для живой стороны (у нас активации нет);
                    // фиксируем, чтобы колонки сопоставлялись по одному набору полей
                    o.put("activationTimeoutMs", step.get("timeoutMs") == null ? 30000 : step.get("timeoutMs"));                    o.put("workerVisibleRetries", payload.get("retries"));
                    o.put("taskHeaders", payload.get("taskHeaders"));
                    o.put("variables", payload.get("variables"));
                    o.put("outcome", "jobObserved");
                }
                case "complete" -> {
                    ActivityEntity activity = active(pi, element);
                    if (activity == null) {
                        o.put("outcome", "noJobToComplete");
                        return o;
                    }
                    runtimeService.completeServiceTask(activity.getId(), toVariables(asMap(step.get("vars"))));
                    o.put("vars", step.get("vars"));
                    o.put("outcome", "completed");
                }
                case "fail" -> {
                    ActivityEntity activity = active(pi, element);
                    if (activity == null) {
                        o.put("outcome", "noJobToFail");
                        return o;
                    }
                    Integer retries = step.get("retries") == null ? null
                        : Integer.valueOf(String.valueOf(step.get("retries")));
                    // Наш fail-API (RuntimeService.failServiceTask) переменных НЕ принимает:
                    // воркер не может отдать partial-результат вместе с отказом
                    // (live-8.5: FailJob(variables) — переменные в локальном скоупе).
                    Object requestedVars = step.get("vars");
                    runtimeService.failServiceTask(activity.getId(), String.valueOf(step.get("message")), retries);
                    o.put("retries", retries);
                    o.put("errorMessage", step.get("message"));
                    o.put("retryBackOffMs", step.get("retryBackOffMs") == null ? 0 : step.get("retryBackOffMs"));
                    o.put("vars", requestedVars);
                    o.put("varsAccepted", requestedVars == null || ((Map<?, ?>) requestedVars).isEmpty());
                    o.put("outcome", "failed");
                }
                case "throwError" -> {
                    ActivityEntity activity = active(pi, element);
                    if (activity == null) {
                        o.put("outcome", "noJobToThrow");
                        return o;
                    }
                    var result = activityService.throwServiceTaskError(activity.getId(),
                        String.valueOf(step.get("code")), List.of());
                    o.put("errorCode", step.get("code"));
                    o.put("errorMessage", step.get("message"));
                    o.put("handled", result.isHandled());
                    o.put("incidentId", result.getIncidentId() == null ? null : result.getIncidentId().toString());
                    o.put("outcome", "thrown");
                }
                case "activateAndAbandon" -> {
                    // У движка нет активации: задание уходит в очередь один раз и
                    // остаётся там, пока воркер его не возьмёт. Таймаута активации
                    // (и, значит, повторной выдачи) у нас нет — см. матрицу.
                    ActivityEntity activity = active(pi, element);
                    o.put("activated", activity == null ? 0 : 1);
                    o.put("outcome", activity == null ? "noJob" : "dispatchedNoActivationTimeout");
                }
                case "wait" -> {
                    o.put("ms", step.get("ms"));
                    o.put("outcome", "waited");
                }
                case "watchdog" -> {
                    // Тот же боевой путь, что у шедулера (WO-REL-35): пакетный
                    // обработчик «зависших» service-task'ов. Cutoff в будущем —
                    // «зависшим» считается всё, что не взяли вовремя.
                    //
                    // Пакетный проход — ГЛОБАЛЬНЫЙ и с лимитом (100 за проход),
                    // поэтому одного прохода может не хватить: в общей базе остаются
                    // «зависшие» задания других кейсов/тестов. Повторяем проходы,
                    // пока инцидент на НАШЕМ элементе не появится (находка
                    // reviewer О-6: иначе порядок строк в БД решает, сработает ли).
                    int raised = 0;
                    int passes = 0;
                    while (passes < 50 && incidentsOf(pi, element).isEmpty()) {
                        raised += stuckServiceTaskBatchProcessor.processBatch(
                            Instant.now().plusSeconds(3600), 100, Duration.ofMinutes(15));
                        passes++;
                    }
                    o.put("incidentsRaised", raised);
                    o.put("watchdogPasses", passes);
                    o.put("outcome", "watchdogRun");
                }
                case "observeIncident" -> {
                    List<Map<String, Object>> incidents = incidentsOf(pi, element);
                    o.put("incidents", incidents);
                    o.put("outcome", incidents.isEmpty() ? "noIncident" : "incident");
                }
                default -> o.put("outcome", "unknownStep");
            }
        } catch (Exception e) {
            o.put("outcome", "error");
            o.put("error", rootMessage(e));
        }
        return o;
    }

    private ActivityEntity active(UUID pi, String elementId) {
        if (elementId == null) {
            return null;
        }
        return activityRepository.findAll().stream()
            .filter(a -> pi.equals(a.getProcessInstanceId()))
            .filter(a -> elementId.equals(a.getBpmnElementId()))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED || a.getStatus() == ActivityStatus.IN_PROGRESS)
            .findFirst()
            .orElse(null);
    }

    /**
     * Тело задания, которое реально видит воркер: outbox-запись (kind=SERVICE_TASK)
     * для этого service-task. Берётся ПОСЛЕДНЯЯ (переотправка после fail создаёт
     * новую запись) — иначе воркер видел бы устаревшее тело.
     */
    private Map<String, Object> jobPayload(UUID pi, UUID serviceTaskId) {
        OutboxEntry latest = null;
        for (OutboxEntry entry : outboxRepository.findAll()) {
            if (entry.getKind() != OutboxKind.SERVICE_TASK) {
                continue;
            }
            Map<String, Object> node = readPayload(entry);
            if (node == null) {
                continue;
            }
            if (!pi.toString().equals(String.valueOf(node.get("processInstanceId")))
                || !serviceTaskId.toString().equals(String.valueOf(node.get("serviceTaskId")))) {
                continue;
            }
            if (latest == null || entry.getCreatedAt().isAfter(latest.getCreatedAt())
                || (entry.getCreatedAt().equals(latest.getCreatedAt())
                    && entry.getId().compareTo(latest.getId()) > 0)) {
                latest = entry;
            }
        }
        if (latest == null) {
            return null;
        }
        Map<String, Object> node = readPayload(latest);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("job", node.get("job"));
        out.put("serviceTaskKey", node.get("serviceTaskKey"));
        out.put("priority", node.get("priority"));
        out.put("taskHeaders", node.get("taskHeaders"));
        @SuppressWarnings("unchecked")
        Map<String, Object> rawVars = (Map<String, Object>) node.get("variables");
        out.put("variables", variablesJson(rawVars));
        return out;
    }

    private Map<String, Object> readPayload(OutboxEntry entry) {
        try {
            return objectMapper.readValue(entry.getPayload(), new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return null;
        }
    }

    /** Переменные задания в виде JSON-строки (как в теле live-8.5 — сырой JSON). */
    private String variablesJson(Map<String, Object> rawVars) {
        if (rawVars == null) {
            return "{}";
        }
        Map<String, Object> plain = new LinkedHashMap<>();
        rawVars.forEach((k, v) -> plain.put(k, unwrap(v)));
        List<String> keys = new ArrayList<>(plain.keySet());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            String key = keys.get(i);
            sb.append('"').append(key).append("\":");
            Object value = plain.get(key);
            if (value == null) {
                sb.append("null");
            } else if (value instanceof String s) {
                sb.append('"').append(s.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            } else if (value instanceof Boolean || value instanceof Number) {
                sb.append(value);
            } else {
                sb.append(objectMapper.writeValueAsString(value));
            }
        }
        return sb.append('}').toString();
    }

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

    /** Остаток бюджета попыток (то, что на live-8.5 воркер видит в поле retries). */
    private Integer remainingRetries(UUID serviceTaskId) {
        for (ServiceTaskEntity entity : serviceTaskRepository.findAll()) {
            if (serviceTaskId.equals(entity.getId())) {
                return entity.getRetriesRemaining();
            }
        }
        return null;
    }

    private List<Map<String, Object>> incidentsOf(UUID pi, String elementId) {
        List<UUID> activityIds = activityRepository.findAll().stream()
            .filter(a -> pi.equals(a.getProcessInstanceId()))
            .filter(a -> elementId == null || elementId.equals(a.getBpmnElementId()))
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

    private List<Map<String, Object>> openIncidents(UUID pi) {
        return incidentsOf(pi, null);
    }

    private List<ProcessVariable> startVariables(Map<String, Object> c) {
        return toVariables(asMap(c.get("vars")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private List<ProcessVariable> toVariables(Map<String, Object> vars) {
        List<ProcessVariable> result = new ArrayList<>();
        for (Map.Entry<String, Object> e : vars.entrySet()) {
            ProcessVariable v = new ProcessVariable();
            v.setName(e.getKey());
            Object value = e.getValue();
            if (value == null) {
                v.setType(ProcessVariableType.JSON);
                v.setValue("null");
            } else if (value instanceof Boolean b) {
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

    private String bpmnXml(Map<String, Object> c) throws Exception {
        String resource = String.valueOf(c.get("bpmn"));
        ClassPathResource res = new ClassPathResource(resource);
        try (InputStream in = res.getInputStream()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /**
     * Объявленный тип задания по элементу (из {@code zeebe:taskDefinition}).
     * Тот же факт, что на live-8.5 виден в поле {@code jobType} активированного
     * задания — нужен, чтобы шаг «задания нет» называл ТИП задания, а не молчал.
     */
    private Map<String, String> declaredJobTypes(String bpmn) {
        Map<String, String> types = new LinkedHashMap<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "<bpmn:(serviceTask|scriptTask|sendTask)\\b[^>]*id=\"([^\"]+)\"[\\s\\S]*?</bpmn:\\1>").matcher(bpmn);
        while (m.find()) {
            java.util.regex.Matcher t = java.util.regex.Pattern.compile(
                "<zeebe:taskDefinition[^>]*\\btype=\"([^\"]+)\"").matcher(m.group());
            if (t.find()) {
                types.put(m.group(2), t.group(1));
            }
        }
        return types;
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    @AfterAll
    void writeObserved() throws Exception {
        Path path = Path.of(observedFile());
        Files.createDirectories(path.getParent());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", java.time.Instant.now().toString());
        out.put("cases", observed);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), out);
    }
}
