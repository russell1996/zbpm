package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-51 часть B: инцидент «выражение коллекции не дало список» обязан
 * объяснять прод-случай потребителя — воркер положил JSON-текст в STRING
 * (сообщение раньше печатало голое значение и не отличало «STRING с JSON» от
 * «пустая переменная» и «воркер не заполнил»).
 *
 * <p>Две поверхности одной семантики: MI {@code inputCollection} и ad-hoc
 * {@code activeElementsCollection}. Каждая комбинация типа значения — отдельный
 * тест: STRING с JSON-текстом, пустая STRING, переменной нет (null), число,
 * объект; плюс зелёные регрессы, что JSON-массив по-прежнему работает.
 *
 * <p>Объект у MI — отдельная красная точка: до фикса scala-Map проходил
 * рефлексию по {@code size()} и молча порождал N инстансов с {@code item=null}
 * вместо инцидента.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class CollectionNotAListDiagnosisIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private UserTaskRepository userTaskRepository;

    private UUID deploy(String bpmnFile) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + bpmnFile));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        return model.getId();
    }

    private UUID start(UUID pdId, List<ProcessVariable> variables) {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        dto.setVariables(variables);
        return runtimeService.startProcessInstance(dto).getId();
    }

    private ProcessVariable var(String name, ProcessVariableType type, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(type);
        v.setValue(value);
        return v;
    }

    private List<ActivityEntity> activities(UUID pi, String bpmnElementId, ActivityStatus status) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getBpmnElementId().equals(bpmnElementId) && a.getStatus() == status)
            .toList();
    }

    private IncidentEntity singleIncident(UUID pi, String bpmnElementId) {
        Set<UUID> activityIds = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getBpmnElementId().equals(bpmnElementId))
            .map(ActivityEntity::getId)
            .collect(Collectors.toSet());
        List<IncidentEntity> incidents = incidentRepository.findAll().stream()
            .filter(i -> activityIds.contains(i.getActivityId()))
            .toList();
        assertThat(incidents).as("exactly one incident on " + bpmnElementId).hasSize(1);
        return incidents.get(0);
    }

    // ─── MI inputCollection ──────────────────────────────────────────────

    /** Зелёный регресс: JSON-массив по-прежнему раскручивает инстансы с item. */
    @Transactional
    @Test
    void mi_jsonArray_spawnsInstancesWithItems() throws Exception {
        UUID pdId = deploy("test-c851-mi-inputcollection-variable.bpmn");
        UUID pi = start(pdId, List.of(var("activities", ProcessVariableType.JSON, "[\"a\",\"b\"]")));

        List<ActivityEntity> tasks = activities(pi, "utMi", ActivityStatus.CREATED);
        assertThat(tasks).as("two MI instances").hasSize(2);
        assertThat(tasks.stream().map(a -> userTaskRepository.findById(a.getId()).map(u -> u.getAssignee()).orElse(null)).toList())
            .containsExactlyInAnyOrder("a", "b");
    }

    /** Прод-случай потребителя: STRING с JSON-текстом — сообщение объясняет, чего ждёт движок. */
    @Transactional
    @Test
    void mi_stringWithJsonText_incidentExplainsStringIsNotParsed() throws Exception {
        UUID pdId = deploy("test-c851-mi-inputcollection-variable.bpmn");
        UUID pi = start(pdId, List.of(var("activities", ProcessVariableType.STRING, "[\"a\",\"b\"]")));

        assertThat(activities(pi, "utMi", ActivityStatus.CREATED)).isEmpty();
        assertThat(activities(pi, "utMi", ActivityStatus.ERROR)).hasSize(1);
        assertThat(singleIncident(pi, "utMi").getMessage())
            .contains("Multi-instance utMi inputCollection did not evaluate to a list")
            .contains("variable 'activities' has type STRING")
            .contains("value: '[\"a\",\"b\"]'")
            .contains("A STRING is not parsed into a collection")
            .contains("type JSON (array)");
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNull();
    }

    @Transactional
    @Test
    void mi_emptyString_incidentSaysVariableIsEmpty() throws Exception {
        UUID pdId = deploy("test-c851-mi-inputcollection-variable.bpmn");
        UUID pi = start(pdId, List.of(var("activities", ProcessVariableType.STRING, "")));

        assertThat(activities(pi, "utMi", ActivityStatus.CREATED)).isEmpty();
        assertThat(singleIncident(pi, "utMi").getMessage())
            .contains("variable 'activities' has type STRING, value: ''")
            .contains("The variable is empty: the worker did not fill the collection.");
    }

    @Transactional
    @Test
    void mi_absentVariable_incidentSaysNotSet() throws Exception {
        UUID pdId = deploy("test-c851-mi-inputcollection-variable.bpmn");
        UUID pi = start(pdId, List.of());

        assertThat(activities(pi, "utMi", ActivityStatus.CREATED)).isEmpty();
        assertThat(singleIncident(pi, "utMi").getMessage())
            .contains("variable 'activities' is not set (null)")
            .contains("The variable is empty: the worker did not fill the collection.");
    }

    @Transactional
    @Test
    void mi_number_incidentSaysListRequired() throws Exception {
        UUID pdId = deploy("test-c851-mi-inputcollection-variable.bpmn");
        UUID pi = start(pdId, List.of(var("activities", ProcessVariableType.LONG, "5")));

        assertThat(activities(pi, "utMi", ActivityStatus.CREATED)).isEmpty();
        assertThat(singleIncident(pi, "utMi").getMessage())
            .contains("variable 'activities' has type LONG, value: '5'")
            .contains("A list is required.");
    }

    /**
     * Красная точка: scala-Map раньше проходил рефлексию по {@code size()} —
     * объект молча порождал инстанс(ы) с item=null, без инцидента.
     */
    @Transactional
    @Test
    void mi_object_incidentInsteadOfSilentNullItemInstances() throws Exception {
        UUID pdId = deploy("test-c851-mi-inputcollection-variable.bpmn");
        UUID pi = start(pdId, List.of(var("activities", ProcessVariableType.JSON, "{\"a\":1}")));

        assertThat(activities(pi, "utMi", ActivityStatus.CREATED))
            .as("object must not silently spawn instances").isEmpty();
        assertThat(singleIncident(pi, "utMi").getMessage())
            .contains("variable 'activities' has type JSON, value: '{\"a\":1}'")
            .contains("(the JSON value is an object, not an array)")
            .contains("A list is required.");
    }

    // ─── ad-hoc activeElementsCollection ─────────────────────────────────

    @Transactional
    @Test
    void adhoc_jsonArray_activatesBothInnerTasks() throws Exception {
        UUID pdId = deploy("test-c851-adhoc-activeelements-variable.bpmn");
        UUID pi = start(pdId, List.of(var("activities", ProcessVariableType.JSON, "[\"taskA\",\"taskB\"]")));

        assertThat(activities(pi, "taskA", ActivityStatus.CREATED)).hasSize(1);
        assertThat(activities(pi, "taskB", ActivityStatus.CREATED)).hasSize(1);
        assertThat(activities(pi, "adhoc", ActivityStatus.CREATED)).hasSize(1);
    }

    @Transactional
    @Test
    void adhoc_stringWithJsonText_incidentExplainsStringIsNotParsed() throws Exception {
        UUID pdId = deploy("test-c851-adhoc-activeelements-variable.bpmn");
        UUID pi = start(pdId, List.of(var("activities", ProcessVariableType.STRING, "[\"taskA\",\"taskB\"]")));

        assertThat(activities(pi, "taskA", ActivityStatus.CREATED)).isEmpty();
        assertThat(activities(pi, "adhoc", ActivityStatus.ERROR)).hasSize(1);
        assertThat(singleIncident(pi, "adhoc").getMessage())
            .contains("Ad-hoc subprocess 'adhoc' activeElementsCollection did not evaluate to a list")
            .contains("variable 'activities' has type STRING")
            .contains("value: '[\"taskA\",\"taskB\"]'")
            .contains("A STRING is not parsed into a collection");
    }

    @Transactional
    @Test
    void adhoc_emptyString_incidentSaysVariableIsEmpty() throws Exception {
        UUID pdId = deploy("test-c851-adhoc-activeelements-variable.bpmn");
        UUID pi = start(pdId, List.of(var("activities", ProcessVariableType.STRING, "")));

        assertThat(activities(pi, "taskA", ActivityStatus.CREATED)).isEmpty();
        assertThat(singleIncident(pi, "adhoc").getMessage())
            .contains("variable 'activities' has type STRING, value: ''")
            .contains("The variable is empty: the worker did not fill the collection.");
    }

    @Transactional
    @Test
    void adhoc_absentVariable_incidentSaysNotSet() throws Exception {
        UUID pdId = deploy("test-c851-adhoc-activeelements-variable.bpmn");
        UUID pi = start(pdId, List.of());

        assertThat(activities(pi, "taskA", ActivityStatus.CREATED)).isEmpty();
        assertThat(singleIncident(pi, "adhoc").getMessage())
            .contains("variable 'activities' is not set (null)")
            .contains("The variable is empty: the worker did not fill the collection.");
    }

    @Transactional
    @Test
    void adhoc_number_incidentSaysListRequired() throws Exception {
        UUID pdId = deploy("test-c851-adhoc-activeelements-variable.bpmn");
        UUID pi = start(pdId, List.of(var("activities", ProcessVariableType.LONG, "5")));

        assertThat(activities(pi, "taskA", ActivityStatus.CREATED)).isEmpty();
        assertThat(singleIncident(pi, "adhoc").getMessage())
            .contains("variable 'activities' has type LONG, value: '5'")
            .contains("A list is required.");
    }

    @Transactional
    @Test
    void adhoc_object_incidentSaysJsonObjectNotArray() throws Exception {
        UUID pdId = deploy("test-c851-adhoc-activeelements-variable.bpmn");
        UUID pi = start(pdId, List.of(var("activities", ProcessVariableType.JSON, "{\"a\":1}")));

        assertThat(activities(pi, "taskA", ActivityStatus.CREATED)).isEmpty();
        assertThat(singleIncident(pi, "adhoc").getMessage())
            .contains("variable 'activities' has type JSON, value: '{\"a\":1}'")
            .contains("(the JSON value is an object, not an array)")
            .contains("A list is required.");
    }
}
