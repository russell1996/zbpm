package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.ListenerModel;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.entity.OutboxKind;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.entity.ServiceTaskEntity;
import com.zorrodev.bpm.engine.handler.ElementSupport;
import com.zorrodev.bpm.engine.leak.LeakInvariantChecker;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.ElementListenerPhaseRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DmnService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-OPS-28 ФАЗА 1 — измерительный харнесс «Задачи и данные» на НАШЕМ движке.
 *
 * <p>Читает ТОТ ЖЕ корпус, что и живой оракул Zeebe 8.5.0
 * ({@code zorrobpm-engine/src/test/resources/ops28-phase1-cases.json}) и ТЕ ЖЕ BPMN-файлы
 * ({@code ops28-phase1/*.bpmn}, сгенерированы одним генератором
 * {@code docs/analysis/c8-oracle/ops28-phase1-gen.js}). Расхождение поведения измеряется,
 * а НЕ «лечится» здесь: правки ядра — отдельными WO (G-N: тест гоняет прод-путь,
 * а не копию логики).</p>
 *
 * <p>Что измеряется на каждом кейсе:</p>
 * <ul>
 *   <li>{@code work} — переменные, видимые элементу под тестом (то, что у Zeebe 8.5 —
 *       payload активированной задачи);</li>
 *   <li>{@code dump.root} — переменные корневого scope после выполнения (то, что у Zeebe 8.5 —
 *       payload задачи-наблюдателя);</li>
 *   <li>{@code dump.visible} — переменные, видимые элементу-наблюдателю;</li>
 *   <li>{@code incidents} — открытые инциденты (парковка токена);</li>
 *   <li>{@code leaks} — нарушения инварианта G-P (WO-OPS-26) для ЭТОГО инстанса.</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class Phase1TaskDataConformanceSupport {

    static final String CASES_RESOURCE = "ops28-phase1-cases.json";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Map<String, Object>> observed = Collections.synchronizedMap(new LinkedHashMap<>());

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private ActivityService activityService;

    @Autowired
    private DmnService dmnService;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private VariableRepository variableRepository;

    @Autowired
    private ProcessInstanceRepository processInstanceRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private ElementSupport elementSupport;

    @Autowired
    private BpmnService bpmnService;

    @Autowired
    private ServiceTaskRepository serviceTaskRepository;

    @Autowired
    private ElementListenerPhaseRepository elementListenerPhaseRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Файл наблюдений этого профиля (профили не затирают друг друга). */
    protected String observedFile() {
        return System.getProperty("ops28.observed.file", "target/ops28-phase1-observed.json");
    }

    Stream<Map<String, Object>> cases() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(CASES_RESOURCE)) {
            assertThat(in).as("корпус кейсов " + CASES_RESOURCE).isNotNull();
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> root = mapper.readValue(in, new TypeReference<Map<String, Object>>() { });
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cases = (List<Map<String, Object>>) root.get("cases");
            String filter = System.getProperty("ops28.cases.filter", "");
            return cases.stream()
                .filter(c -> filter.isBlank() || String.valueOf(c.get("id")).startsWith(filter))
                .toList().stream();
        }
    }

    /** Само измерение кейса. Точка входа — в конкретном классе-профиле. */
    protected void measureCase(Map<String, Object> c) throws Exception {
        String id = String.valueOf(c.get("id"));
        Map<String, Object> obs = new LinkedHashMap<>();
        obs.put("id", id);
        obs.put("element", c.get("element"));
        obs.put("title", c.get("title"));
        obs.put("docs", c.get("docs"));
        // Что именно наблюдаем как «payload задания» (work) — по корпусу.
        Object observeElement = c.get("observeElement");
        if (observeElement == null && c.get("drive") instanceof List<?> list && !list.isEmpty()
            && list.get(0) instanceof Map<?, ?> first) {
            observeElement = first.get("element");
        }
        obs.put("observeWork", Boolean.TRUE.equals(c.get("observeWork")));
        obs.put("observeElement", observeElement == null ? null : String.valueOf(observeElement));

        // G-P: baseline ДО кейса (WO-OPS-26; API после WO-OPS-32: снимок «все инстансы +
        // завершённые» — по нему чекер отличает НАШИ инстансы от чужих общей БД).
        LeakInvariantChecker.Baseline baseline = LeakInvariantChecker.snapshot(jdbcTemplate);

        UUID pi = null;
        UUID definitionId = null;
        try {
            if (c.get("dmn") != null) {
                dmnService.deploy(resource(String.valueOf(c.get("dmn"))));
            }
            if (c.get("child") != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> child = (Map<String, Object>) c.get("child");
                processDefinitionService.addProcessDefinition(resource(String.valueOf(child.get("bpmn"))));
            }
            var definition = processDefinitionService.addProcessDefinition(bpmnSource(c));
            pi = null;
            definitionId = definition.getId();
        } catch (Exception e) {
            obs.put("outcome", "deployError");
            obs.put("error", rootMessage(e));
            obs.put("incidents", List.of());
            // Инстанс не создан → инвариант утечек НЕ проверялся (не «проверено и чисто»).
            obs.put("leaksChecked", Boolean.FALSE);
            obs.put("leaks", List.of());
            observed.put(id, obs);
            // Кейс не задеплоился на НАШЕМ движке. В корпусе нет кейса, который
            // у нас по ожиданию должен падать при деплое, поэтому это расхождение с ожиданием,
            // и оно обязано ронять тест: иначе кейс молча выпал бы из измерения (а в матрице
            // выглядел бы как «ошибка деплоя/старта» — ложное расхождение с оракулом).
            assertThat(assertExpectation(c, obs)).as("ожидание корпуса применено к кейсу %s", id).isTrue();
            return;
        }
        try {
            StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
            dto.setProcessDefinitionId(definitionId);
            dto.setVariables(toVariables(mapOf(c.get("vars"))));
            pi = runtimeService.startProcessInstance(dto).getId();
        } catch (Exception e) {
            // Старт провалился — отдельный исход (та же лексика, что у оракула:
            // deployError / startError), чтобы матрица сравнивала ОДИН И ТОТ ЖЕ факт.
            obs.put("outcome", "startError");
            obs.put("error", rootMessage(e));
            obs.put("incidents", List.of());
            obs.put("leaksChecked", Boolean.FALSE);
            obs.put("leaks", List.of());
            observed.put(id, obs);
            assertThat(assertExpectation(c, obs)).as("ожидание корпуса применено к кейсу %s", id).isTrue();
            return;
        }
        obs.put("processInstanceId", pi.toString());

        try {
            for (Map<String, Object> step : steps(c)) {
                drive(pi, step, obs);
            }
        } catch (Exception e) {
            obs.put("outcome", "driveError");
            obs.put("error", rootMessage(e));
        }

        // Наблюдатель: переменные корневого scope после выполнения кейса.
        // Кейс без наблюдателя (P-15: процесс без start-события) — смотреть нечего.
        String dumpElement = c.get("dump") == null ? null : String.valueOf(c.get("dump"));
        ActivityEntity dump = dumpElement == null ? null : activeActivity(pi, dumpElement);
        if (dump == null) {
            obs.put("outcome", obs.containsKey("outcome") ? obs.get("outcome") : "stuck");
            obs.put("dump", null);
        } else {
            obs.put("dump.visible", visibleVars(pi, dump.getId()));
            obs.put("dump.root", rootVars(pi));
            runtimeService.completeServiceTask(dump.getId(), List.of());
            obs.put("outcome", "completed");
        }

        obs.put("vars.all", allVars(pi));
        obs.put("incidents", openIncidents(pi));
        obs.put("instanceCompleted", instanceCompleted(pi));
        obs.put("leaksChecked", Boolean.TRUE);
        obs.put("leaks", leaks(baseline, pi));
        observed.put(id, obs);
        // G-P: нарушение инварианта утечек роняет тест, а не просто попадает в отчёт.
        assertThat((List<?>) obs.get("leaks")).as("инвариант утечек G-P для кейса %s", id).isEmpty();
        // Ожидание корпуса обязано быть применено: кейс без ожидания ничего бы не проверял
        // и остался бы зелёным «по незнанию».
        assertThat(assertExpectation(c, obs)).as("ожидание корпуса применено к кейсу %s", id).isTrue();
    }

    /**
     * Сверка измерения с ожиданием.
     *
     * <p>Ожидание берётся из ЖИВОГО Zeebe 8.5.0 (корпус собран скриптом
     * {@code docs/analysis/c8-oracle/ops28-phase1-expect.js} из измерения оракула) — не из
     * нашей реализации (G-Q). Для кейсов с зафиксированным расхождением навязывается
     * НАШЕ измеренное поведение (characterization) + в наблюдение пишется ссылка на баг-WO:
     * иначе расхождение просто не проверялось бы, и тест был бы зелёным «по незнанию».</p>
     *
     * @return {@code true}, если ожидание корпуса было применено (иначе — {@code false}:
     *         кейс без ожидания ничего не проверяет, и вызывающий обязан это увидеть).
     */
    private boolean assertExpectation(Map<String, Object> c, Map<String, Object> obs) {
        String id = String.valueOf(c.get("id"));
        if (!(c.get("expect") instanceof Map<?, ?> expect)) {
            return false;
        }
        boolean divergence = c.get("divergence") != null;
        obs.put("divergence", c.get("divergence"));
        @SuppressWarnings("unchecked")
        Map<String, Object> wanted = divergence
            ? (Map<String, Object>) expect.get("ours")
            : (Map<String, Object>) expect.get("live");
        if (wanted == null) {
            return false;
        }
        String outcome = String.valueOf(wanted.get("outcome"));
        assertThat(obs.get("outcome")).as("исход кейса %s", id).isEqualTo(outcome);
        Object dump = wanted.get("dump");
        if (dump instanceof Map<?, ?> map && !map.isEmpty()) {
            Map<String, Object> expected = new LinkedHashMap<>();
            // Тот же приведение типов, что в Ф1: целое JSON-число у оракула приходит Integer,
            // у движка — LONG. Сравниваем ЗНАЧЕНИЯ, а не JSON-типы.
            map.forEach((k, v) -> expected.put(String.valueOf(k), normalizeNumbers(v)));
            assertThat(canon(obs.get("dump.root"))).as("переменные root в кейсе %s", id).isEqualTo(expected);
        }
        // Заголовки задания (zeebe:taskHeaders) — отдельное измерение: у Zeebe это
        // job.customHeaders, у нас — taskHeaders в теле задания (outbox).
        Object headers = wanted.get("taskHeaders");
        if (headers instanceof Map<?, ?> map) {
            Map<String, Object> expectedHeaders = new LinkedHashMap<>();
            map.forEach((k, v) -> expectedHeaders.put(String.valueOf(k), v));
            Object ours = ((Map<?, ?>) obs.get("work.outbox")) == null
                ? null : ((Map<?, ?>) obs.get("work.outbox")).get("taskHeaders");
            // «Заголовков нет» — это пустая карта с обеих сторон (Zeebe отдаёт {}, у нас null).
            Map<String, Object> oursHeaders = ours instanceof Map<?, ?> m
                ? canon(m) : Map.of();
            assertThat(oursHeaders).as("заголовки задания в кейсе %s", id).isEqualTo(expectedHeaders);
        }
        // Слушатели (P-5): «есть ли задание слушателя этого типа» — тот же вопрос, что
        // задаёт оракул активацией job'а. Сверяем ТОЛЬКО нашу сторону (у оракула своё
        // наблюдение — в его JSON), и сверяем ЯВНО: present/absent, а не «что-то вызвалось».
        Object listener = wanted.get("listener");
        if (listener instanceof Map<?, ?> map) {
            Map<String, Object> expectedListener = new LinkedHashMap<>();
            map.forEach((k, v) -> expectedListener.put(String.valueOf(k), v));
            Object oursListener = obs.get("listener");
            Map<String, Object> oursMap = oursListener instanceof Map<?, ?> m
                ? castMap(m) : Map.of();
            assertThat(oursMap).as("наличие задания слушателя в кейсе %s", id).isEqualTo(expectedListener);
        }
        return true;
    }

    /** Наша карта переменных → плоские значения (как у Zeebe). */
    private Map<String, Object> canon(Object raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            map.forEach((k, v) -> out.put(String.valueOf(k), canonValue(v)));
        }
        return out;
    }

    private Object canonValue(Object entry) {
        if (!(entry instanceof Map<?, ?> wrapper) || !wrapper.containsKey("value")) {
            return entry;
        }
        Object value = wrapper.get("value");
        String type = String.valueOf(wrapper.get("type"));
        try {
            return switch (type) {
                case "LONG" -> Long.parseLong(String.valueOf(value));
                case "DOUBLE" -> Double.parseDouble(String.valueOf(value));
                case "BOOLEAN" -> Boolean.parseBoolean(String.valueOf(value));
                case "JSON" -> normalizeNumbers(objectMapper.readValue(String.valueOf(value), Object.class));
                default -> String.valueOf(value);
            };
        } catch (Exception e) {
            throw new IllegalStateException("unreadable variable wrapper " + entry, e);
        }
    }

    private static Object normalizeNumbers(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), normalizeNumbers(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(Phase1TaskDataConformanceSupport::normalizeNumbers).toList();
        }
        if (value instanceof Integer i) {
            return i.longValue();
        }
        return value;
    }

    /* ------------------------------------------------------------------ драйв */

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> steps(Map<String, Object> c) {
        Object drive = c.get("drive");
        return drive instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private void drive(UUID pi, Map<String, Object> step, Map<String, Object> obs) {
        String kind = String.valueOf(step.get("kind"));
        String element = String.valueOf(step.get("element"));
        switch (kind) {
            case "auto" -> {
                // элемент исполняется сам (script/BRT/send-message) — ничего делать не надо
            }
            case "message" -> {
                String name = String.valueOf(step.get("message"));
                String key = step.get("key") == null ? null : String.valueOf(step.get("key"));
                var result = activityService.publishMessage(name, key, pi, toVariables(mapOf(step.get("vars"))));
                Map<String, Object> publish = new LinkedHashMap<>();
                publish.put("name", name);
                publish.put("key", key);
                publish.put("correlated", result.getCorrelated());
                obs.put("publish", publish);
            }
            case "service", "user" -> {
                UUID target = pi;
                if (Boolean.TRUE.equals(step.get("anyPi"))) {
                    // шаг относится к ДОЧЕРНЕМУ инстансу (call activity) — у него свой pi
                    target = childInstance(pi);
                }
                ActivityEntity activity = activeActivity(target, element);
                if (activity == null) {
                    obs.put("driveMissing", element);
                    return;
                }
                if (Boolean.TRUE.equals(obs.get("observeWork")) && !obs.containsKey("work")
                    && element.equals(String.valueOf(obs.get("observeElement")))) {
                    obs.put("work", visibleVars(target, activity.getId()));
                    obs.put("work.outbox", outboxPayload(target, activity.getId()));
                }
                List<ProcessVariable> vars = toVariables(mapOf(step.get("vars")));
                if ("user".equals(kind)) {
                    runtimeService.completeUserTask(activity.getId(), vars);
                } else {
                    runtimeService.completeServiceTask(activity.getId(), vars);
                }
            }
            case "listener" -> {
                // «Существует ли задание слушателя?» — тот же вопрос, что у оракула
                // (там активируется job типа listenerType). У нас воркерскую сторону
                // смотрим по ОТКРЫТОЙ ФАЗЕ слушателя элемента: движок ставит индекс фазы
                // (service_tasks.pending_listener_index / pending_end_listener_index),
                // когда задание слушателя ушло воркеру. Фазы нет → слушателя нет.
                String type = String.valueOf(step.get("jobType"));
                UUID target = pi;
                ActivityEntity activity = activeActivity(target, element);
                Map<String, Object> listener = obs.get("listener") instanceof Map<?, ?> m
                    ? castMap(m) : new LinkedHashMap<>();
                obs.put("listener", listener);
                if (activity == null) {
                    listener.put(type, "absent");
                    obs.put("listenerMissing", element);
                    return;
                }
                String openType = openListenerJobType(target, activity, type);
                listener.put(type, openType != null ? "present" : "absent");
                Map<String, Object> modelView = obs.get("listenerModel") instanceof Map<?, ?> m
                    ? castMap(m) : new LinkedHashMap<>();
                obs.put("listenerModel", modelView);
                modelView.put(type, listenerKnownInModel(target, activity, type) ? "в модели" : "нет в модели");
                if (openType != null) {
                    // Слушатель есть — завершаем его так же, как это сделал бы воркер.
                    // Протокол завершения — по типу элемента: у user task своя ветка
                    // (WO-C8-21: creating-слушатель закрывается через completeUserTask).
                    if (isUserTask(target, activity)) {
                        runtimeService.completeUserTask(activity.getId(), toVariables(mapOf(step.get("vars"))));
                    } else {
                        runtimeService.completeServiceTask(activity.getId(), toVariables(mapOf(step.get("vars"))));
                    }
                }
            }
            default -> throw new IllegalStateException("неизвестный вид шага: " + kind);
        }
    }

    /**
     * Тип задания ОТКРЫТОЙ фазы слушателя элемента (null — открытой фазы нет).
     *
     * <p>Вопрос, который задаёт шаг {@code kind:'listener'}: «существует ли задание
     * слушателя этого типа?» У оракула это активация job'а типа {@code listenerType};
     * у нас воркерскую сторону видно по ОТКРЫТОЙ ФАЗЕ: движок ставит индекс фазы
     * (у задачи — {@code service_tasks.pending_listener_index} /
     * {@code pending_end_listener_index}, у user task — индексы на activity,
     * у прочих элементов — {@code element_listener_phases.listener_index}), когда
     * задание слушателя ушло воркеру. Фазы нет → слушателя нет.</p>
     *
     * <p>Индексы НЕ выдумываются: каждый взят из своей таблицы, список слушателей —
     * из разобранной модели элемента (того же, которым пользуется рантайм).</p>
     */
    private String openListenerJobType(UUID pi, ActivityEntity activity, String wantedType) {
        BpmnElementModel element = elementModel(pi, activity.getBpmnElementId());
        if (element == null) {
            return null;
        }
        // 1) задача (service task / send task с taskDefinition) — фаза в service_tasks.
        ServiceTaskEntity serviceTask = serviceTaskRepository
            .findAll(ServiceTaskRepository.byProcessInstanceId(pi)).stream()
            .filter(t -> activity.getId().equals(t.getId()))
            .findFirst().orElse(null);
        if (serviceTask != null) {
            String start = listenerTypeAt(elementSupport.serviceTaskStartListeners(element),
                serviceTask.getPendingListenerIndex(), wantedType);
            if (start != null) {
                return start;
            }
            String end = listenerTypeAt(elementSupport.serviceTaskEndListeners(element),
                serviceTask.getPendingEndListenerIndex(), wantedType);
            if (end != null) {
                return end;
            }
        }
        // 2) user task — фаза на activity.
        String creating = listenerTypeAt(elementSupport.userTaskCreatingListeners(element),
            activity.getPendingCreatingListenerIndex(), wantedType);
        if (creating != null) {
            return creating;
        }
        String completing = listenerTypeAt(elementSupport.userTaskCompletingListeners(element),
            activity.getPendingCompletingListenerIndex(), wantedType);
        if (completing != null) {
            return completing;
        }
        // 3) прочий элемент (шлюз/событие) — фаза в element_listener_phases.
        for (var phase : elementListenerPhaseRepository.findAll()) {
            if (!pi.equals(phase.getProcessInstanceId())) {
                continue;
            }
            if (!activity.getBpmnElementId().equals(phase.getBpmnElementId())) {
                continue;
            }
            String hit = listenerTypeAt(elementSupport.elementStartListeners(element),
                phase.getListenerIndex(), wantedType);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /**
     * Есть ли слушатель этого типа В МОДЕЛИ элемента (без учёта фазы).
     *
     * <p>Отдельно от {@link #openListenerJobType}: показывает, знает ли движок о слушателе
     * вообще. Для P-5 (слушатели теряются молча) это и есть суть: у Zeebe 8.5 слушатель
     * есть, у нас — нет даже в модели.</p>
     */
    private boolean listenerKnownInModel(UUID pi, ActivityEntity activity, String wantedType) {
        BpmnElementModel element = elementModel(pi, activity.getBpmnElementId());
        if (element == null) {
            return false;
        }
        List<List<ListenerModel>> all = List.of(
            elementSupport.serviceTaskStartListeners(element),
            elementSupport.serviceTaskEndListeners(element),
            elementSupport.userTaskCreatingListeners(element),
            elementSupport.userTaskCompletingListeners(element),
            elementSupport.elementStartListeners(element));
        for (List<ListenerModel> listeners : all) {
            for (ListenerModel l : listeners) {
                if (l != null && wantedType.equals(l.jobType())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Тип слушателя на открытой фазе (index) — только если это ИМЕННО искомый тип. */
    private String listenerTypeAt(List<ListenerModel> listeners, Integer index, String wantedType) {
        if (listeners == null || listeners.isEmpty() || index == null) {
            return null;
        }
        if (index < 0 || index >= listeners.size()) {
            return null;
        }
        ListenerModel listener = listeners.get(index);
        return listener != null && wantedType.equals(listener.jobType()) ? wantedType : null;
    }

    /** Модель элемента инстанса (та же, что читает рантайм). */
    private BpmnElementModel elementModel(UUID pi, String elementId) {
        var instance = processInstanceRepository.findById(pi).orElse(null);
        if (instance == null) {
            return null;
        }
        BpmnProcessDefinitionModel bpmn =
            bpmnService.getProcessDefinitionModelById(instance.getProcessDefinitionId());
        return bpmn == null ? null : bpmn.getElement(elementId);
    }

    /** Тип элемента — user task или нет (для выбора протокола завершения). */
    private boolean isUserTask(UUID pi, ActivityEntity activity) {
        BpmnElementModel element = elementModel(pi, activity.getBpmnElementId());
        return element != null && element.getType() == BpmnElementType.USER_TASK;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private UUID childInstance(UUID parent) {        List<com.zorrodev.bpm.engine.entity.ProcessInstanceEntity> children = processInstanceRepository
            .findAll(ProcessInstanceRepository.byParentProcessInstanceId(parent));
        assertThat(children).as("дочерний инстанс вызова(call activity)").hasSize(1);
        return children.get(0).getId();
    }

    /* ------------------------------------------------------------- наблюдения */

    /** Переменные, видимые элементу (то же, что кладёт в payload задания enqueue-сервис). */
    private Map<String, Object> visibleVars(UUID pi, UUID activityId) {
        List<ProcessVariable> vars = elementSupport.visibleVariables(pi, activityId);
        Map<String, Object> out = new LinkedHashMap<>();
        for (ProcessVariable v : vars) {
            out.put(v.getName(), Map.of("type", String.valueOf(v.getType()), "value", String.valueOf(v.getValue())));
        }
        return out;
    }

    /** Payload задания из outbox (реальный путь enqueue-сервиса); null — под профилем test его не пишут. */
    private Object outboxPayload(UUID pi, UUID activityId) {
        for (OutboxEntry entry : outboxRepository.findAll()) {
            if (entry.getKind() != OutboxKind.SERVICE_TASK) {
                continue;
            }
            try {
                Map<String, Object> node = objectMapper.readValue(entry.getPayload(),
                    new TypeReference<Map<String, Object>>() { });
                if (!pi.toString().equals(String.valueOf(node.get("processInstanceId")))
                    || !activityId.toString().equals(String.valueOf(node.get("serviceTaskId")))) {
                    continue;
                }
                // Всё тело задания, как его видит воркер: переменные, заголовки, тип, приоритет.
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("variables", node.get("variables"));
                body.put("taskHeaders", node.get("taskHeaders"));
                body.put("job", node.get("job"));
                body.put("priority", node.get("priority"));
                return body;
            } catch (Exception e) {
                return "unreadable:" + e.getClass().getSimpleName();
            }
        }
        return null;
    }

    /** Корневые переменные инстанса (scope_id IS NULL) — аналог payload задания-наблюдателя у Zeebe. */
    private Map<String, Object> rootVars(UUID pi) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (ProcessVariableEntity v : variableRepository.findByProcessInstanceIdAndScopeIdIsNull(pi)) {
            out.put(v.getName(), Map.of("type", String.valueOf(v.getType()), "value", String.valueOf(v.getTextValue())));
        }
        return out;
    }

    /** Все переменные инстанса (включая scoped) — показывает, что осталось в скоупах. */
    private Map<String, Object> allVars(UUID pi) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (ProcessVariableEntity v : variableRepository.findByProcessInstanceId(pi)) {
            out.put(v.getName() + "@" + (v.getScopeId() == null ? "root" : "scope"),
                Map.of("type", String.valueOf(v.getType()), "value", String.valueOf(v.getTextValue())));
        }
        return out;
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

    private boolean instanceCompleted(UUID pi) {
        var instance = processInstanceRepository.findById(pi);
        return instance.isPresent() && instance.get().getCompletedAt() != null;
    }

    /**
     * G-P: нарушения инварианта утечек для ЭТОГО инстанса (WO-OPS-26).
     * Чекер смотрит только ЗАВЕРШЁННЫЕ инстансы, поэтому для «зависшего» кейса
     * список пуст по построению — это отмечено в отчёте (пробел L-2 инвентаря).
     */
    private List<String> leaks(LeakInvariantChecker.Baseline baseline, UUID pi) {
        // Только «свои» нарушения (own): инстанс кейса создан после baseline; чужие
        // инстансы общей БД (созданные ДО baseline, завершённые в его окне) — артефакт
        // изоляции и к этому кейсу не относятся (WO-OPS-32).
        return LeakInvariantChecker.check(jdbcTemplate, baseline).own().stream()
            .filter(v -> pi.equals(v.instanceId()))
            .map(LeakInvariantChecker.Violation::toString)
            .toList();
    }

    /* ------------------------------------------------------------------ утилиты */

    private ActivityEntity activeActivity(UUID pi, String elementId) {
        return activityRepository.findAll().stream()
            .filter(a -> pi.equals(a.getProcessInstanceId()))
            .filter(a -> elementId.equals(a.getBpmnElementId()))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED || a.getStatus() == ActivityStatus.IN_PROGRESS)
            .findFirst()
            .orElse(null);
    }

    private String resource(String path) throws Exception {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            assertThat(in).as("ресурс " + path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * BPMN кейса: либо сгенерированный файл корпуса, либо ВНЕШНЯЯ фикстура
     * ({@code src/test/files/...}) — тот же файл читает оракул (P-15 инвентаря фазы 0:
     * фикстура {@code process0.bpmn} не упоминалась ни одним тестом).
     */
    private String bpmnSource(Map<String, Object> c) throws Exception {
        Object file = c.get("bpmnFile");
        if (file != null) {
            Path path = Paths.get("src/test/files", String.valueOf(file));
            assertThat(Files.exists(path)).as("внешняя фикстура %s", path).isTrue();
            return Files.readString(path, StandardCharsets.UTF_8);
        }
        return resource(String.valueOf(c.get("bpmn")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapOf(Object raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
        }
        return out;
    }

    /** JSON-значение корпуса → типизированная переменная движка (та же конвенция, что в Ф1). */
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
                v.setValue(String.valueOf(value));
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

    @AfterAll
    void writeObserved() throws Exception {
        java.nio.file.Path path = java.nio.file.Path.of(observedFile());
        java.nio.file.Files.createDirectories(path.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), observed);
    }
}
