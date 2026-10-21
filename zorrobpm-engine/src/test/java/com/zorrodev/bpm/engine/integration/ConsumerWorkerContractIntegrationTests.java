package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-OPS-30 Ф2в, п.4 инвентаря — КОНТРАКТНЫЕ тесты для воркеров-потребителей.
 *
 * <p>Воркеры консьюмера (Go) живут ВНЕ этого репозитория, поэтому контракт
 * проверяется с нашей стороны: что движок ОБЕЩАЕТ воркеру.
 * <ul>
 *   <li><b>входные переменные по маппингу</b> — source → target, тип переменной сохраняется
 *       (JSON остаётся JSON, STRING остаётся STRING);</li>
 *   <li><b>слияние результата</b> — переменные completion попадают в скоуп и видны
 *       следующей задаче (в т.ч. переменная типа JSON);</li>
 *   <li><b>ошибка → инцидент</b> — отказ воркера (retries=0) поднимает инцидент
 *       на элементе с текстом отказа; сам инцидент — на элементе задания.</li>
 * </ul>
 *
 * <p>Всё меряется по БОЕВОМУ пути (G-N): {@code RuntimeService} → handler'ы → outbox-тело
 * задания (то, что реально получает воркер) → таблицы {@code activities}/{@code incidents}.
 * Никакой копии логики движка в тесте нет.
 *
 * <p>Имя класса оканчивается на {@code IntegrationTests} — иначе его не заберёт
 * ни surefire, ни failsafe (урок WO-C8-40).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class ConsumerWorkerContractIntegrationTests {

    private static final String BPMN = "message/f2v-contract-worker.bpmn";

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
    private IncidentRepository incidentRepository;

    private UUID pi;

    @BeforeEach
    @Transactional
    void startInstance() throws Exception {
        String xml;
        try (InputStream in = new ClassPathResource(BPMN).getInputStream()) {
            xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        var definition = processDefinitionService.addProcessDefinition(xml);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(definition.getId());
        dto.setVariables(List.of(
            variable("order", ProcessVariableType.JSON, "{\"id\":\"ORD-7\",\"total\":42}"),
            variable("payload", ProcessVariableType.JSON, "{\"items\":[1,2,3]}"),
            variable("label", ProcessVariableType.STRING, "hello")));
        pi = runtimeService.startProcessInstance(dto).getId();
    }

    /**
     * Контракт 1: входные переменные по маппингу доезжают до воркера с теми же именами
     * (target) и типами (JSON / STRING). Проверяется ТЕЛО ЗАДАНИЯ — то, что воркер видит.
     */
    @Test
    @Transactional
    void inputVariablesAreDeliveredToTheWorker_withMappedNamesAndTypes() {
        Map<String, Object> body = jobBody("work");
        Map<String, Object> vars = variablesOf(body);

        assertThat(vars).as("mapped inputs in the job body").containsKeys("orderId", "payloadIn", "labelIn");
        assertThat(vars.get("orderId")).as("order.id mapped to orderId").isEqualTo("ORD-7");
        assertThat(vars.get("payloadIn")).as("JSON payload survives the mapping").isEqualTo("{\"items\":[1,2,3]}");
        assertThat(vars.get("labelIn")).as("STRING label survives the mapping").isEqualTo("hello");

        // ТИПЫ: JSON остаётся JSON, STRING остаётся STRING (воркер различает их по типу).
        assertThat(typeOf(body, "payloadIn")).as("payloadIn type").isEqualTo(ProcessVariableType.JSON);
        assertThat(typeOf(body, "labelIn")).as("labelIn type").isEqualTo(ProcessVariableType.STRING);
        assertThat(typeOf(body, "orderId")).as("orderId type").isEqualTo(ProcessVariableType.STRING);
    }

    /**
     * Контракт 2: результат completion сливается в скоуп — следующая задача видит
     * переменные воркера (в т.ч. переменную типа JSON).
     */
    @Test
    @Transactional
    void completionVariablesAreMergedIntoTheScope_andVisibleToTheNextTask() {
        ActivityEntity work = activity("work");
        runtimeService.completeServiceTask(work.getId(), List.of(
            variable("result", ProcessVariableType.STRING, "done"),
            variable("details", ProcessVariableType.JSON, "{\"ok\":true}")));

        Map<String, Object> body = jobBody("afterWork");
        Map<String, Object> vars = variablesOf(body);
        assertThat(vars).as("completion variables merged into the scope")
            .containsEntry("result", "done")
            .containsEntry("details", "{\"ok\":true}");
        assertThat(typeOf(body, "details")).as("details type").isEqualTo(ProcessVariableType.JSON);
    }

    /**
     * Контракт 3: отказ воркера (бюджет попыток исчерпан) поднимает ИНЦИДЕНТ на элементе
     * с текстом отказа — воркер не может «потерять» задание молча.
     */
    @Test
    @Transactional
    void workerFailureWithNoRetriesLeft_raisesIncidentOnTheElement() {
        ActivityEntity work = activity("work");
        runtimeService.failServiceTask(work.getId(), "worker exploded", 0);

        List<IncidentEntity> incidents = incidentRepository.findByActivityIdInAndCompletedAtIsNull(
            List.of(work.getId()));
        assertThat(incidents).as("incident raised on the failed element").hasSize(1);
        assertThat(incidents.get(0).getMessage()).as("incident message keeps the worker's text")
            .contains("worker exploded");
    }

    // ---- helpers -------------------------------------------------------------------------

    private ProcessVariable variable(String name, ProcessVariableType type, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(type);
        v.setValue(value);
        return v;
    }

    private ActivityEntity activity(String elementId) {
        return activityRepository.findAll().stream()
            .filter(a -> pi.equals(a.getProcessInstanceId()))
            .filter(a -> elementId.equals(a.getBpmnElementId()))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED || a.getStatus() == ActivityStatus.IN_PROGRESS)
            .sorted(java.util.Comparator.comparing(ActivityEntity::getCreatedAt,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                .thenComparing(a -> a.getId().toString()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no activity for element " + elementId));
    }

    /** Тело задания, которое реально видит воркер (outbox-запись kind=SERVICE_TASK). */
    private Map<String, Object> jobBody(String elementId) {
        ActivityEntity activity = activity(elementId);
        OutboxEntry latest = null;
        for (OutboxEntry entry : outboxRepository.findAll()) {
            if (entry.getKind() != OutboxKind.SERVICE_TASK) {
                continue;
            }
            Map<String, Object> node = readPayload(entry);
            if (node == null
                || !pi.toString().equals(String.valueOf(node.get("processInstanceId")))
                || !activity.getId().toString().equals(String.valueOf(node.get("serviceTaskId")))) {
                continue;
            }
            if (latest == null || entry.getCreatedAt().isAfter(latest.getCreatedAt())
                || (entry.getCreatedAt().equals(latest.getCreatedAt())
                    && entry.getId().compareTo(latest.getId()) > 0)) {
                latest = entry;
            }
        }
        assertThat(latest).as("outbox job body for element " + elementId).isNotNull();
        return readPayload(latest);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> variablesOf(Map<String, Object> body) {
        Object raw = body.get("variables");
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(String.valueOf(k), v instanceof Map<?, ?> w && w.containsKey("value")
                ? w.get("value") : v));
            return out;
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private ProcessVariableType typeOf(Map<String, Object> body, String name) {
        Object raw = body.get("variables");
        if (!(raw instanceof Map<?, ?> m)) {
            return null;
        }
        Object wrapper = m.get(name);
        if (wrapper instanceof Map<?, ?> w && w.get("type") != null) {
            return ProcessVariableType.valueOf(String.valueOf(w.get("type")));
        }
        return null;
    }

    private Map<String, Object> readPayload(OutboxEntry entry) {
        try {
            return objectMapper.readValue(entry.getPayload(), new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return null;
        }
    }

    /** Неиспользуемый, но оставленный явным: список всех переменных задания (для диагностики). */
    List<String> variableNames(Map<String, Object> body) {
        return new ArrayList<>(variablesOf(body).keySet());
    }
}
