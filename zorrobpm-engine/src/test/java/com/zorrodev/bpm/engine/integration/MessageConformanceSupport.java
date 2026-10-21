package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.PublishMessageDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.entity.OutboxKind;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.MessageSubscriptionRepository;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.DmnService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-OPS-30 Ф2в — измерение СООБЩЕНИЙ (+ script/DMN) на нашем движке: колонка «ours»
 * матрицы {@code docs/analysis/message-conformance-matrix.md}.
 *
 * <p>Тот же список кейсов, что и на живом Zeebe 8.5
 * ({@code zorrobpm-engine/src/test/resources/message-cases.json}) — это ЕДИНСТВЕННЫЙ
 * источник правды: модель BPMN и сами шаги. Оракул
 * ({@code docs/analysis/c8-oracle/messages.js}) исполняет те же шаги против живого
 * брокера и кладёт рядом свои наблюдения.
 *
 * <p>G-N: каждое наблюдение снято с ЖИВОГО пути движка — {@code RuntimeService} /
 * {@code ActivityService} → handler'ы → тело задания в outbox (то, что реально видит
 * воркер) → таблицы {@code activities} / {@code process_instances}. Копии логики
 * движка в тесте нет.
 *
 * <p>Ожиданий этот класс НЕ придумывает: он меряет и пишет
 * {@code target/message-observed.json}; сверка с живым 8.5 — в матрице (G-Q).
 * Ассерты — только на то, что измерение состоялось, и на инвариант утечек (G-P).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class MessageConformanceSupport {

    static final String CASES_RESOURCE = "message-cases.json";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Map<String, Object>> observed = Collections.synchronizedMap(new LinkedHashMap<>());

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private ActivityService activityService;

    @Autowired
    private DBService dbService;

    @Autowired
    private DmnService dmnService;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private MessageSubscriptionRepository messageSubscriptionRepository;

    @Autowired
    private ProcessInstanceRepository processInstanceRepository;

    /** Файл наблюдений этого прогона (колонка ours матрицы). */
    protected String observedFile() {
        return System.getProperty("message.observed.file", "target/message-observed.json");
    }

    Stream<Map<String, Object>> cases() throws Exception {
        try (InputStream in = MessageConformanceSupport.class.getClassLoader()
                .getResourceAsStream(CASES_RESOURCE)) {
            assertThat(in).as("case list resource " + CASES_RESOURCE).isNotNull();
            Map<String, Object> root = new ObjectMapper().readValue(in, new TypeReference<Map<String, Object>>() { });
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cases = (List<Map<String, Object>>) root.get("cases");
            String filter = System.getProperty("message.cases.filter", "");
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

        List<Map<String, Object>> observations = new ArrayList<>();

        // DMN-решение (кейс f2v-dmn-called) живёт рядом с BPMN: на живой стороне
        // его НЕ существует (8.5 не знает zeebe:calledDecision), у нас — есть.
        if (c.get("dmn") != null) {
            dmnService.deploy(resourceText(String.valueOf(c.get("dmn"))));
        }

        // Публикация ДО деплоя модели (кейс про буфер): подписки ещё нет.
        if (Boolean.TRUE.equals(c.get("publishBeforeDeploy"))) {
            for (Map<String, Object> step : steps(c)) {
                if ("publish".equals(String.valueOf(step.get("action")))) {
                    observations.add(publish(step, null));
                }
            }
        }

        ProcessDefinition definition;
        String xml;
        try {
            xml = resourceText(String.valueOf(c.get("bpmn")));
            definition = processDefinitionService.addProcessDefinition(xml);
        } catch (Exception e) {
            observations.add(Map.of("step", "deploy", "outcome", "deployError", "error", rootMessage(e)));
            obs.put("observations", observations);
            obs.put("outcome", "deployError");
            observed.put(id, obs);
            return;
        }
        observations.add(Map.of("step", "deploy", "outcome", "deployOk"));
        Map<String, String> declaredTypes = declaredJobTypes(xml);

        UUID pi = null;
        if (!"message".equals(String.valueOf(c.get("start")))) {
            try {
                StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
                dto.setProcessDefinitionId(definition.getId());
                dto.setVariables(toVariables(asMap(c.get("vars"))));
                pi = runtimeService.startProcessInstance(dto).getId();
            } catch (Exception e) {
                observations.add(Map.of("step", "start", "outcome", "startError", "error", rootMessage(e)));
                obs.put("observations", observations);
                obs.put("outcome", "startError");
                observed.put(id, obs);
                return;
            }
        }
        obs.put("processInstanceId", pi == null ? null : pi.toString());

        for (Map<String, Object> step : steps(c)) {
            if (Boolean.TRUE.equals(c.get("publishBeforeDeploy"))
                    && "publish".equals(String.valueOf(step.get("action")))) {
                continue; // уже опубликовано до деплоя
            }
            String action = String.valueOf(step.get("action"));
            Map<String, Object> observation;
            if ("publish".equals(action)) {
                observation = publish(step, pi);
            } else if ("observeJob".equals(action) || "completeJob".equals(action)) {
                observation = jobStep(action, step, pi, declaredTypes);
            } else if ("observeState".equals(action)) {
                observation = observeState(pi);
            } else {
                observation = Map.of("step", action, "outcome", "unknownStep");
            }
            observations.add(observation);
            // Инстанс, созданный публикацией сообщения (message start event),
            // становится известен только после неё — дальше шаги работают с ним.
            if (pi == null && "publish".equals(action)) {
                Object started = observation.get("started");
                if (started instanceof Integer n && n > 0) {
                    pi = instanceOfDefinition(definition.getId());
                }
            }
        }

        obs.put("observations", observations);
        obs.put("finalActivities", pi == null ? Map.of() : finalActivities(pi));
        obs.put("instanceVariables", pi == null ? Map.of() : instanceVariables(pi));
        obs.put("openIncidentCount", pi == null ? 0 : incidentsOf(pi));
        observed.put(id, obs);
        assertThat(obs.get("observations")).as("case %s produced observations", id).isNotNull();
        assertExpectations(c, observations);
    }

    /**
     * Сверка с ожиданиями кейса. Ожидания — ИЗМЕРЕНИЕ на живом Zeebe 8.5
     * (G-Q: источник ожиданий — оракул, не наша реализация); для известных
     * расхождений ожидание фиксирует НАШЕ измеренное поведение, а измеренное
     * значение живой стороны лежит рядом в {@code liveReference}.
     */
    private void assertExpectations(Map<String, Object> c, List<Map<String, Object>> observations) {
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
            if (want.containsKey("variables")) {
                assertThat(parseVars(got.get("variables"))).as("variables of %s in case %s", want.get("step"), id)
                    .isEqualTo(parseVars(want.get("variables")));
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

    /** Публикация сообщения — боевой путь движка (тот же, что у REST-эндпоинта). */
    private Map<String, Object> publish(Map<String, Object> step, UUID pi) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("step", "publish");
        o.put("element", step.get("element"));
        o.put("messageName", step.get("name"));
        o.put("correlationKey", step.get("correlationKey"));
        // TTL/буферизация: наш API публикации такого параметра НЕ имеет (Camunda
        // PublishMessage — timeToLive). Это НЕ молчаливый прогон: заявленный TTL
        // виден в наблюдении, чтобы расхождение нельзя было принять за «совпало».
        o.put("timeToLiveRequested", step.get("timeToLive"));
        o.put("timeToLiveSupported", false);
        try {
            PublishMessageDTO dto = new PublishMessageDTO();
            dto.setMessageName(String.valueOf(step.get("name")));
            if (step.get("correlationKey") != null) {
                dto.setCorrelationKey(String.valueOf(step.get("correlationKey")));
            }
            dto.setVariables(toVariables(asMap(step.get("variables"))));
            var result = runtimeService.publishMessage(dto);
            o.put("correlated", result.getCorrelated());
            o.put("started", result.getStarted());
            // Подписки на это сообщение (с их сохранённым ключом корреляции) — видно,
            // ПОЧЕМУ сообщение correlated или нет (важно для расхождений по ключу).
            List<Map<String, Object>> subs = new ArrayList<>();
            for (var e : messageSubscriptionRepository.findAll()) {
                if (String.valueOf(step.get("name")).equals(e.getMessageName())) {
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("correlationKey", e.getCorrelationKey());
                    s.put("consumed", e.isConsumed());
                    s.put("boundaryElementId", e.getBoundaryElementId());
                    subs.add(s);
                }
            }
            o.put("subscriptions", subs);
            o.put("outcome", "published");
        } catch (Exception e) {
            o.put("outcome", "error");
            o.put("error", rootMessage(e));
        }
        return o;
    }

    /** Наблюдение задания элемента / его завершение. */
    private Map<String, Object> jobStep(String action, Map<String, Object> step, UUID pi,
            Map<String, String> declaredTypes) {
        String element = step.get("element") == null ? null : String.valueOf(step.get("element"));
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("step", action);
        o.put("element", element);
        String declaredType = declaredTypes.get(element);
        if (pi == null) {
            // Инстанса нет — наблюдение «задания нет» то же, что на живой стороне
            // (там инстанс может быть, а задания нет). Отличие видно по instancePresent.
            o.put("jobType", declaredType);
            o.put("instancePresent", false);
            o.put("outcome", "noJob");
            return o;
        }
        ActivityEntity activity = active(pi, element);
        if (activity == null) {
            o.put("jobType", declaredType);
            o.put("outcome", "noJob");
            return o;
        }
        Map<String, Object> payload = jobPayload(pi, activity.getId());
        if (payload == null) {
            // Активность есть, но задание не отдано (наш движок не выдаёт задание
            // элементам, которые не являются сервис-задачей, — например броску
            // сообщения: у нас бросок выполняется внутри движка).
            o.put("jobType", declaredType);
            o.put("outcome", "noJob");
            o.put("workActivity", activity.getStatus().toString());
            return o;
        }
        o.put("jobType", payload.get("job"));
        o.put("activityRows", activityRows(pi, element));
        if ("observeJob".equals(action)) {
            o.put("variables", payload.get("variables"));
            o.put("outcome", "jobObserved");
            return o;
        }
        runtimeService.completeServiceTask(activity.getId(), toVariables(asMap(step.get("vars"))));
        o.put("vars", step.get("vars"));
        o.put("outcome", "completed");
        return o;
    }

    /** Итоговое состояние активностей и переменных инстанса (наша сторона). */
    private Map<String, Object> observeState(UUID pi) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("step", "observeState");
        if (pi == null) {
            o.put("instancePresent", false);
            o.put("outcome", "noInstance");
            return o;
        }
        o.put("instancePresent", true);
        o.put("activities", finalActivities(pi));
        o.put("variables", instanceVariables(pi));
        o.put("outcome", "stateObserved");
        return o;
    }

    private ActivityEntity active(UUID pi, String elementId) {
        if (elementId == null) {
            return null;
        }
        // Детерминированный выбор: несколько строк активности одного элемента
        // (например, хост и его повторный вход) — берём САМУЮ СТАРУЮЮ живую.
        // findFirst() по findAll() зависит от порядка JPA и давал нестабильное
        // наблюдение (2026-10-21, прогон против живого 8.5).
        return activityRepository.findAll().stream()
            .filter(a -> pi.equals(a.getProcessInstanceId()))
            .filter(a -> elementId.equals(a.getBpmnElementId()))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED || a.getStatus() == ActivityStatus.IN_PROGRESS)
            .sorted(java.util.Comparator.comparing(ActivityEntity::getCreatedAt,
                    java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                .thenComparing(a -> a.getId().toString()))
            .findFirst()
            .orElse(null);
    }

    /** Сколько строк активности у элемента в инстансе (диагностика выбора строки). */
    private int activityRows(UUID pi, String elementId) {
        return (int) activityRepository.findAll().stream()
            .filter(a -> pi.equals(a.getProcessInstanceId()))
            .filter(a -> elementId.equals(a.getBpmnElementId()))
            .count();
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

    /** Переменные инстанса (что видит воркер в теле задания). */
    private Map<String, Object> instanceVariables(UUID pi) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (ProcessVariable v : dbService.getVariables(pi)) {
            out.put(v.getName(), v.getValue());
        }
        return out;
    }

    private int incidentsOf(UUID pi) {
        List<UUID> activityIds = activityRepository.findAll().stream()
            .filter(a -> pi.equals(a.getProcessInstanceId()))
            .map(ActivityEntity::getId)
            .toList();
        if (activityIds.isEmpty()) {
            return 0;
        }
        return incidentRepository.findByActivityIdInAndCompletedAtIsNull(activityIds).size();
    }

    /**
     * Тело задания, которое реально видит воркер: outbox-запись (kind=SERVICE_TASK)
     * для этого элемента. Берётся ПОСЛЕДНЯЯ (переотправка после fail создаёт новую).
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
        out.put("variables", variablesJson(node.get("variables")));
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
    @SuppressWarnings("unchecked")
    private String variablesJson(Object rawVars) {
        if (!(rawVars instanceof Map<?, ?> raw)) {
            return "{}";
        }
        Map<String, Object> plain = new LinkedHashMap<>();
        raw.forEach((k, v) -> plain.put(String.valueOf(k), unwrap(v)));
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

    /** Инстанс, созданный публикацией сообщения (message start event). */
    protected UUID instanceOfDefinition(UUID definitionId) {
        ProcessInstanceEntity newest = null;
        for (ProcessInstanceEntity e : processInstanceRepository.findAll()) {
            if (!definitionId.equals(e.getProcessDefinitionId())) {
                continue;
            }
            if (newest == null || e.getStartedAt().isAfter(newest.getStartedAt())) {
                newest = e;
            }
        }
        return newest == null ? null : newest.getId();
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

    private String resourceText(String resource) throws Exception {
        ClassPathResource res = new ClassPathResource(resource);
        try (InputStream in = res.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Объявленный тип задания по элементу (из {@code zeebe:taskDefinition}) — тот же
     * факт, что на live-8.5 виден в поле {@code jobType} активированного задания.
     * Разбор — по открывающим тегам: жадный {@code [\s\S]*?} по закрывающему тегу
     * склеивал бы соседние элементы (та же ошибка, что была в оракуле).
     */
    private Map<String, String> declaredJobTypes(String bpmn) {
        Map<String, String> types = new LinkedHashMap<>();
        java.util.regex.Matcher open = java.util.regex.Pattern.compile(
            "<bpmn:([a-zA-Z]+)\\b([^>]*?)(\\/)?>").matcher(bpmn);
        while (open.find()) {
            java.util.regex.Matcher id = java.util.regex.Pattern.compile(
                "\\bid=\"([^\"]+)\"").matcher(open.group(2));
            if (!id.find()) {
                continue;
            }
            String body = "";
            if (!"/".equals(open.group(3))) {
                String close = "</bpmn:" + open.group(1) + ">";
                int closeIdx = bpmn.indexOf(close, open.end());
                if (closeIdx > 0) {
                    body = bpmn.substring(open.end(), closeIdx);
                }
            }
            java.util.regex.Matcher t = java.util.regex.Pattern.compile(
                "<zeebe:taskDefinition[^>]*\\btype=\"([^\"]+)\"").matcher(body);
            if (t.find()) {
                types.put(id.group(1), t.group(1));
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
