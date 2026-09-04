package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import com.zorrodev.bpm.engine.service.DmnService;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import com.zorrodev.bpm.engine.handler.ElementSupport;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-C8-1 (Фаза 0) — эмпирическая характеризация BPMN/DMN-паритета с Camunda 8.
 * База: {@code docs/analysis/camunda8-bpmn-gap-audit.md} (§C.1/§C.2/§C.4 + "Ограничения этого
 * захода"). ТОЛЬКО характеризация (V7): каждый тест фиксирует РЕАЛЬНО наблюдаемое поведение
 * движка на реалистичном C8-Modeler XML — даже если оно неверное. Ничего не чинит.
 *
 * <p>Стиль — как у соседних {@code *IntegrationTests}: полный Spring-контекст на H2,
 * {@code @Transactional} (откат после теста), деплой через реальный
 * {@code ProcessDefinitionService}, запуск через реальный {@code RuntimeService}.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class Camunda8ParityCharacterizationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private BpmnParseService bpmnParseService;

    @Autowired
    private DmnService dmnService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ElementSupport elementSupport;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private VariableRepository variableRepository;

    private static String uniq(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String bpmn(String file) throws Exception {
        return Files.readString(Paths.get("src/test/files/" + file));
    }

    private ProcessVariable var(String name, ProcessVariableType type, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(type);
        v.setValue(value);
        return v;
    }

    private UUID start(UUID processDefinitionId, List<ProcessVariable> variables) {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(processDefinitionId);
        dto.setVariables(variables);
        return runtimeService.startProcessInstance(dto).getId();
    }

    private ActivityEntity activity(UUID processInstanceId, String bpmnElementId) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .filter(a -> a.getBpmnElementId().equals(bpmnElementId))
            .findFirst().orElseThrow();
    }

    private List<IncidentEntity> incidentsOfInstance(UUID processInstanceId) {
        Set<UUID> activityIds = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .map(ActivityEntity::getId)
            .collect(Collectors.toSet());
        return incidentRepository.findAll().stream()
            .filter(i -> activityIds.contains(i.getActivityId()))
            .toList();
    }

    private void completeUserTask(UUID processInstanceId, String bpmnElementId) {
        UserTaskQuery query = new UserTaskQuery();
        query.setProcessInstanceId(processInstanceId);
        PagedDataDTO<UserTask> tasks = queryService.findUserTasks(query, null);
        UUID userTaskId = tasks.getData().stream()
            .filter(t -> t.getCompletedAt() == null)
            .findFirst().orElseThrow().getId();
        assertThat(activity(processInstanceId, bpmnElementId).getStatus())
            .as("user task activity is parked, not finished")
            .isNotEqualTo(ActivityStatus.COMPLETED);
        runtimeService.completeUserTask(userTaskId, List.of());
    }

    // ==================== §C.1: zeebe:taskHeaders ====================

    @Test
    @Transactional
    void taskHeaders_parsedAndDelivered_serviceTaskParksWithoutIncident() throws Exception {
        // WO-C8-7 GREEN (переименован из taskHeaders_areSilentlyIgnored_serviceTaskParksWithoutIncident):
        // headers парсятся из фикстуры; доставку до JobDetailModel/outbox доказывает
        // ServiceTaskEnqueueServiceImplTest (включая реальный JSON payload).
        String key = uniq("c8th");
        String xml = bpmn("test-c8-task-headers.bpmn").replace("c8-task-headers", key);

        assertThat(bpmnParseService.parse(xml).getElement("svc").getExtensions()
            .getServiceTaskExtension().getTaskHeaders())
            .containsExactlyInAnyOrderEntriesOf(Map.of("tenant", "acme", "priority", "high"));

        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();
        assertThat(activity(piId, "svc").getStatus()).isEqualTo(ActivityStatus.CREATED);
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    // ==================== §C.1: zeebe:executionListeners ====================

    @Test
    @Transactional
    void executionListeners_areSilentlyIgnored_serviceTaskParksWithoutIncident() throws Exception {
        String key = uniq("c8el");
        String xml = bpmn("test-c8-execution-listeners.bpmn").replace("c8-exec-listeners", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();
        assertThat(activity(piId, "svc").getStatus()).isEqualTo(ActivityStatus.CREATED);
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    // ==================== §C.1: zeebe:taskListeners ====================

    @Test
    @Transactional
    void taskListeners_doNotBreakUserTaskFlow() throws Exception {
        String key = uniq("c8tl");
        String xml = bpmn("test-c8-task-listeners.bpmn").replace("c8-task-listeners", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();
        completeUserTask(piId, "review");

        ProcessInstance pi = queryService.getProcessInstance(piId);
        assertThat(pi.getCompletedAt()).isNotNull();
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    // ==================== §C.1: zeebe:priorityDefinition ====================

    @Test
    @Transactional
    void priorityDefinition_parsedAndDelivered_serviceTaskParksWithoutIncident() throws Exception {
        // WO-C8-9 GREEN (переименован из priorityDefinition_isSilentlyIgnored_serviceTaskParksWithoutIncident):
        // priority парсится из фикстуры; доставку до JobDetailModel/outbox доказывает
        // ServiceTaskEnqueueServiceImplTest (включая реальный JSON payload).
        String key = uniq("c8pr");
        String xml = bpmn("test-c8-priority.bpmn").replace("c8-priority", key);

        assertThat(bpmnParseService.parse(xml).getElement("svc").getExtensions()
            .getServiceTaskExtension().getPriority())
            .isEqualTo("75");

        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();
        assertThat(activity(piId, "svc").getStatus()).isEqualTo(ActivityStatus.CREATED);
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    @Test
    @Transactional
    void priorityDefinition_feelExpression_resolvesToInteger() throws Exception {
        // WO-C8-9, критерий 1 (FEEL-форма): `=priorityVar` вычисляется через
        // elementSupport.resolvePriority в Integer на реальной переменной инстанса.
        String key = uniq("c8prf");
        String xml = bpmn("test-c8-priority.bpmn")
            .replace("c8-priority", key)
            .replace("priority=\"75\"", "priority=\"=priorityVar\"");
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of(var("priorityVar", ProcessVariableType.LONG, "75")));

        assertThat(elementSupport.resolvePriority(piId, bpmnParseService.parse(xml).getElement("svc")))
            .isEqualTo(75);

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();
        assertThat(activity(piId, "svc").getStatus()).isEqualTo(ActivityStatus.CREATED);
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    @Test
    @Transactional
    void priorityDefinition_brokenValue_resolvesToNullWithoutIncident() throws Exception {
        // WO-C8-9, критерий 4: не-Integer — не исключение, не инцидент: резолвится в null,
        // задача паркуется штатно (информационное поле, не триггер).
        String key = uniq("c8prb");
        String xml = bpmn("test-c8-priority.bpmn")
            .replace("c8-priority", key)
            .replace("priority=\"75\"", "priority=\"not-a-number\"");
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());

        assertThat(elementSupport.resolvePriority(piId, bpmnParseService.parse(xml).getElement("svc")))
            .isNull();

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();
        assertThat(activity(piId, "svc").getStatus()).isEqualTo(ActivityStatus.CREATED);
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    // ==================== §C.1: zeebe:versionTag ====================

    @Test
    @Transactional
    void versionTag_isSilentlyIgnored_serviceTaskParksWithoutIncident() throws Exception {
        String key = uniq("c8vt");
        String xml = bpmn("test-c8-version-tag.bpmn").replace("c8-version-tag", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();
        assertThat(activity(piId, "svc").getStatus()).isEqualTo(ActivityStatus.CREATED);
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    // ==================== §C.1: zeebe:taskSchedule ====================

    @Test
    @Transactional
    void taskSchedule_doesNotBlockOverdueTask() throws Exception {
        String key = uniq("c8ts");
        String xml = bpmn("test-c8-task-schedule.bpmn").replace("c8-task-schedule", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());

        // dueDate is long past — the task still parks normally and completes on demand.
        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();
        // WO-C8-8: resolved values really landed on the task (not just parsed and dropped).
        UserTaskQuery parkedQuery = new UserTaskQuery();
        parkedQuery.setProcessInstanceId(piId);
        UserTask parked = queryService.findUserTasks(parkedQuery, null).getData().stream()
            .filter(t -> t.getCompletedAt() == null)
            .findFirst().orElseThrow();
        assertThat(parked.getDueDate()).isEqualTo("2020-01-01T00:00:00Z");
        assertThat(parked.getFollowUpDate()).isEqualTo("2020-01-02T00:00:00Z");
        completeUserTask(piId, "review");

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    @Test
    @Transactional
    void taskSchedule_feelExpression_resolvesAgainstVariables() throws Exception {
        // WO-C8-8: `=dueDate`-форма (как в test-mi-reenter.bpmn) вычисляется через
        // elementSupport.resolveExpression, а не копируется буквально.
        String key = uniq("c8tsf");
        String xml = bpmn("test-c8-task-schedule.bpmn")
            .replace("c8-task-schedule", key)
            .replace("dueDate=\"2020-01-01T00:00:00Z\" followUpDate=\"2020-01-02T00:00:00Z\"",
                "dueDate=\"= shipDate\" followUpDate=\"= shipDate\"");
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of(var("shipDate", ProcessVariableType.STRING, "2030-05-01")));

        UserTaskQuery parkedQuery = new UserTaskQuery();
        parkedQuery.setProcessInstanceId(piId);
        UserTask parked = queryService.findUserTasks(parkedQuery, null).getData().stream()
            .filter(t -> t.getCompletedAt() == null)
            .findFirst().orElseThrow();
        assertThat(parked.getDueDate()).isEqualTo("2030-05-01");
        assertThat(parked.getFollowUpDate()).isEqualTo("2030-05-01");
        completeUserTask(piId, "review");

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    @Test
    @Transactional
    void taskSchedule_brokenExpression_storesNullWithoutIncident() throws Exception {
        // WO-C8-8, критерий 5: битый FEEL — не исключение, не инцидент: хранится null,
        // задача паркуется и завершается штатно (информационное поле, не триггер).
        String key = uniq("c8tsb");
        String xml = bpmn("test-c8-task-schedule.bpmn")
            .replace("c8-task-schedule", key)
            .replace("dueDate=\"2020-01-01T00:00:00Z\" followUpDate=\"2020-01-02T00:00:00Z\"",
                "dueDate=\"= 1 +\" followUpDate=\"= 1 +\"");
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());

        UserTaskQuery parkedQuery = new UserTaskQuery();
        parkedQuery.setProcessInstanceId(piId);
        UserTask parked = queryService.findUserTasks(parkedQuery, null).getData().stream()
            .filter(t -> t.getCompletedAt() == null)
            .findFirst().orElseThrow();
        assertThat(parked.getDueDate()).isNull();
        assertThat(parked.getFollowUpDate()).isNull();
        completeUserTask(piId, "review");

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    // ==================== §C.1: formId ====================

    @Test
    @Transactional
    void formId_isSilentlyIgnored_userTaskCompletesNormally() throws Exception {
        String key = uniq("c8fi");
        String xml = bpmn("test-c8-form-id.bpmn").replace("c8-form-id", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();
        completeUserTask(piId, "review");

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    // ==================== §C.1: UserTaskForm ====================

    @Test
    @Transactional
    void inlineUserTaskForm_doesNotBreakDeployOrExecution() throws Exception {
        String key = uniq("c8utf");
        String xml = bpmn("test-c8-user-task-form.bpmn").replace("c8-user-task-form", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();
        completeUserTask(piId, "review");

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    // ==================== §C.1: DMN literal expression ====================

    @Test
    @Transactional
    void dmnLiteralExpression_evaluatesDirectly() throws Exception {
        // WO-C8-6 GREEN (переименован из dmnLiteralExpression_failsWithNoDecisionTable):
        // decision без таблицы вычисляет literal FEEL-выражение напрямую.
        String decision = uniq("c8lit");
        dmnService.deploy(bpmn("test-c8-dmn-literal.dmn").replace("c8lit", decision));
        String key = uniq("c8brl");
        String xml = bpmn("test-c8-br-literal.bpmn")
            .replace("c8-br-literal", key)
            .replace("c8lit", decision);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());

        ProcessInstance pi = queryService.getProcessInstance(piId);
        assertThat(pi.getCompletedAt()).isNotNull();
        ProcessVariableEntity greeting = variableRepository
            .findByNameAndProcessInstanceId("greeting", piId).orElseThrow();
        assertThat(greeting.getTextValue()).isEqualTo("hello");
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    @Test
    @Transactional
    void dmnLiteralExpression_seesInputVariables() throws Exception {
        // WO-C8-6, критерий 3: выражение видит входные переменные, не только константы.
        String decision = uniq("c8litvar");
        dmnService.deploy(bpmn("test-c8-dmn-literal.dmn")
            .replace("c8lit", decision)
            .replace("\"hello\"", "amount * 2"));
        String key = uniq("c8brl");
        String xml = bpmn("test-c8-br-literal.bpmn")
            .replace("c8-br-literal", key)
            .replace("c8lit", decision);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of(var("amount", ProcessVariableType.LONG, "21")));

        ProcessInstance pi = queryService.getProcessInstance(piId);
        assertThat(pi.getCompletedAt()).isNotNull();
        ProcessVariableEntity greeting = variableRepository
            .findByNameAndProcessInstanceId("greeting", piId).orElseThrow();
        assertThat(greeting.getType()).isEqualTo(ProcessVariableType.LONG);
        assertThat(greeting.getTextValue()).isEqualTo("42");
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    // ==================== §C.1: DMN decision requirements graph ====================

    @Test
    @Transactional
    void dmnDependencyGraph_isResolved_requiredDecisionFeedsIntoParent() throws Exception {
        // WO-C8-10 GREEN (переименован из dmnDependencyGraph_isNotResolved_topEvaluatesAlone):
        // фикстура поправлена (input `b` → `c8base` — реальная C8-семантика биндинга под
        // decisionId); c8base реально вычисляется (10 для tier="gold"), c8top матчит
        // Rule_top_ok → "TOP-OK" (не "TOP-MISS").
        // DMN деплоится БЕЗ uniq-переименования id: input c8top ссылается на decisionId c8base
        // напрямую через FEEL, а FEEL-идентификатор не может содержать "-" (разделитель
        // uniq()) — иначе имя распарсится как вычитание. Прецедент фиксированных id —
        // DmnEvaluationIntegrationTests.deployAll.
        dmnService.deploy(bpmn("test-c8-dmn-drg.dmn"));
        String key = uniq("c8brd");
        String xml = bpmn("test-c8-br-drg.bpmn").replace("c8-br-drg", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of(var("tier", ProcessVariableType.STRING, "gold")));

        ProcessInstance pi = queryService.getProcessInstance(piId);
        assertThat(pi.getCompletedAt()).isNotNull();
        ProcessVariableEntity result = variableRepository
            .findByNameAndProcessInstanceId("top", piId).orElseThrow();
        assertThat(result.getTextValue()).isEqualTo("TOP-OK");
    }

    @Test
    @Transactional
    void dmnDependencyGraph_multiOutputRequiredDecision_dotAccessWorks() throws Exception {
        // WO-C8-10, критерий 3: multi-output required decision биндится Map'ом под свой
        // decisionId — зависимая decision читает `mbase.discount` через FEEL dot-access
        // (20 + 5 = 25 для tier="gold").
        dmnService.deploy(bpmn("test-c8-dmn-drg-multi.dmn"));

        Object result = dmnService.evaluate("mtop", List.of(var("tier", ProcessVariableType.STRING, "gold")));

        assertThat(((Number) result).intValue()).isEqualTo(25);
    }

    @Test
    @Transactional
    void dmnDependencyGraph_cycle_throwsEngineExceptionNotStackOverflow() throws Exception {
        // WO-C8-10, критерий 4: A требует B, B требует A — EngineException про цикл,
        // не StackOverflowError.
        dmnService.deploy(bpmn("test-c8-dmn-drg-cycle.dmn"));

        assertThatThrownBy(() -> dmnService.evaluate("cycA", List.of(var("x", ProcessVariableType.LONG, "1"))))
            .isInstanceOf(EngineException.class)
            .hasMessageContaining("cycle");
    }

    // ==================== §C.1 + критерий 3: Manual Task ====================

    @Test
    @Transactional
    void manualTask_passesThroughAndCompletes() throws Exception {
        // WO-C8-5 GREEN (переименован из manualTask_breaksFlowWithMissingTargetIncident):
        // manual task — pass-through узел: инстанс проходит насквозь и завершается, 0 инцидентов.
        // Активность manual мгновенно COMPLETED (не паркуется в ожидании, в отличие от user task).
        String key = uniq("c8man");
        String xml = bpmn("test-c8-manual-task.bpmn").replace("c8-manual-task", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        assertThat(activity(piId, "manual").getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    @Test
    @Transactional
    void manualTask_insideSubprocess_passesThrough() throws Exception {
        // WO-C8-5, п.5: manual task внутри embedded subprocess парсится и проходится так же.
        String key = uniq("c8mansub");
        String xml = bpmn("test-c8-manual-subprocess.bpmn").replace("c8-manual-subprocess", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        assertThat(activity(piId, "manual").getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    // ==================== §C.1: processIdExpression / businessId ====================

    @Test
    @Transactional
    void processIdExpression_isNotParsed_callActivityReportsMissingProcessId() throws Exception {
        String key = uniq("c8pie");
        String xml = bpmn("test-c8-call-procidexpr.bpmn").replace("c8-call-procidexpr", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of(var("suffix", ProcessVariableType.STRING, "x")));

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();
        List<IncidentEntity> incidents = incidentsOfInstance(piId);
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getMessage()).contains("has no zeebe:calledElement processId");
    }

    @Test
    @Transactional
    void businessId_isSilentlyIgnored_childRunsNormally() throws Exception {
        String childKey = uniq("c8child");
        processDefinitionService.addProcessDefinition(
            bpmn("test-c8-child.bpmn")
                .replace("c8-child", childKey)
                .replace("C8MARKER", "\"v1\""));
        String key = uniq("c8bid");
        String xml = bpmn("test-c8-call-businessid.bpmn")
            .replace("c8-call-businessid", key)
            .replace("c8-child", childKey);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    // ==================== §C.1: calledDecision bindingType ====================

    @Test
    @Transactional
    void calledDecisionBindingType_isIgnored_latestDecisionWins() throws Exception {
        String decision = uniq("c8pinned");
        dmnService.deploy(bpmn("test-c8-pinned-decision.dmn")
            .replace("c8pinned", decision)
            .replace("C8VAL", "\"v1-val\""));
        String key = uniq("c8cdb");
        String xml = bpmn("test-c8-called-decision-binding.bpmn")
            .replace("c8-called-decision-binding", key)
            .replace("c8pinned", decision);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);
        // a newer version of the same decision lands AFTER the caller was deployed.
        dmnService.deploy(bpmn("test-c8-pinned-decision.dmn")
            .replace("c8pinned", decision)
            .replace("C8VAL", "\"v2-val\""));

        UUID piId = start(model.getId(), List.of(var("tier", ProcessVariableType.STRING, "gold")));

        // bindingType="deployment" is not honoured: the latest decision version wins.
        ProcessInstance pi = queryService.getProcessInstance(piId);
        assertThat(pi.getCompletedAt()).isNotNull();
        ProcessVariableEntity picked = variableRepository
            .findByNameAndProcessInstanceId("picked", piId).orElseThrow();
        assertThat(picked.getTextValue()).isEqualTo("v2-val");
    }

    // ==================== §C.2: processId как FEEL (WO-C8-2 GREEN) ====================

    @Test
    @Transactional
    void feelProcessId_expressionResolvesToChild() throws Exception {
        // WO-C8-2 GREEN (переписан из feelProcessId_resolvesLiterally_baselineForWoC8_2):
        // FEEL-выражение в processId вычисляется против переменных инстанса.
        String childKey = uniq("c8p");
        processDefinitionService.addProcessDefinition(
            bpmn("test-c8-child.bpmn")
                .replace("c8-child", childKey)
                .replace("C8MARKER", "\"v1\""));
        String suffix = childKey.substring(childKey.lastIndexOf('-') + 1);
        String key = uniq("c8fpid");
        String xml = bpmn("test-c8-feel-process-id.bpmn")
            .replace("c8-feel-process-id", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of(var("suffix", ProcessVariableType.STRING, suffix)));

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    @Test
    @Transactional
    void feelProcessId_nullResolve_parksInformativeIncident() throws Exception {
        // WO-C8-2, критерий 4: выражение есть, значения нет — явный инцидент с текстом
        // выражения, а не NPE и не "processId вообще не указан".
        String key = uniq("c8fpid");
        String xml = bpmn("test-c8-feel-process-id.bpmn")
            .replace("c8-feel-process-id", key)
            .replace("= &quot;c8p-&quot; + suffix", "= null");
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();
        List<IncidentEntity> incidents = incidentsOfInstance(piId);
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getMessage())
            .contains("'call'")
            .contains("= null")
            .contains("resolved to null/blank");
    }

    @Test
    @Transactional
    void feelProcessId_brokenExpression_parksInformativeIncident() throws Exception {
        // WO-C8-2, критерий 4: синтаксически битый FEEL — тот же явный путь (warn + null).
        String key = uniq("c8fpid");
        String xml = bpmn("test-c8-feel-process-id.bpmn")
            .replace("c8-feel-process-id", key)
            .replace("= &quot;c8p-&quot; + suffix", "= 1 +");
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of(var("suffix", ProcessVariableType.STRING, "x")));

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();
        List<IncidentEntity> incidents = incidentsOfInstance(piId);
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getMessage()).contains("resolved to null/blank");
    }

    // ==================== §C.2: bindingType=deployment (эмпирика latest) ====================

    @Test
    @Transactional
    void bindingTypeDeployment_resolvesLatestNotPinned() throws Exception {
        String childKey = uniq("c8callee");
        processDefinitionService.addProcessDefinition(
            bpmn("test-c8-child.bpmn")
                .replace("c8-child", childKey)
                .replace("C8MARKER", "\"v1\""));
        String key = uniq("c8bt");
        String xml = bpmn("test-c8-binding-deployment.bpmn")
            .replace("c8-binding-deployment", key)
            .replace("c8-callee", childKey);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);
        // новая версия вызываемого приземляется ПОСЛЕ деплоя вызывающего.
        processDefinitionService.addProcessDefinition(
            bpmn("test-c8-child.bpmn")
                .replace("c8-child", childKey)
                .replace("C8MARKER", "\"v2\""));

        UUID piId = start(model.getId(), List.of());

        // bindingType="deployment" не соблюдается: выполняется latest (v2), не зафиксированная v1.
        // (Маркер пишется и в дочернем, и — WO-ENG-11 propagation — в родительском инстансе,
        // поэтому смотрим именно строку дочернего инстанса.)
        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        List<ProcessVariableEntity> childMarkers = variableRepository.findAll().stream()
            .filter(v -> v.getName().equals("ranVersion"))
            .filter(v -> !v.getProcessInstanceId().equals(piId))
            .toList();
        assertThat(childMarkers).hasSize(1);
        assertThat(childMarkers.get(0).getTextValue()).isEqualTo("v2");
    }

    // ==================== WO-C8-3: bindingType="versionTag" ====================

    @Test
    @Transactional
    void bindingTypeVersionTag_pinsToTaggedVersion() throws Exception {
        // v1 несёт тег v1, v2 (latest) — без тега. Вызывающий просит versionTag="v1":
        // выполняется ИМЕННО v1, не latest.
        String childKey = uniq("c8callee");
        processDefinitionService.addProcessDefinition(
            bpmn("test-c8-child-tagged.bpmn")
                .replace("c8-child-tagged", childKey)
                .replace("C8TAG", "v1")
                .replace("C8MARKER", "\"v1\""));
        processDefinitionService.addProcessDefinition(
            bpmn("test-c8-child-tagged.bpmn")
                .replace("c8-child-tagged", childKey)
                .replace("C8TAG", "v2-latest")
                .replace("C8MARKER", "\"v2\""));
        String key = uniq("c8btv");
        String xml = bpmn("test-c8-binding-version-tag.bpmn")
            .replace("c8-binding-version-tag", key)
            .replace("c8-callee-tagged", childKey);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        List<ProcessVariableEntity> childMarkers = variableRepository.findAll().stream()
            .filter(v -> v.getName().equals("ranVersion"))
            .filter(v -> !v.getProcessInstanceId().equals(piId))
            .toList();
        assertThat(childMarkers).hasSize(1);
        assertThat(childMarkers.get(0).getTextValue()).isEqualTo("v1");
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    @Test
    @Transactional
    void bindingTypeVersionTag_noMatchingVersion_parksInformativeIncident() throws Exception {
        // Тег, которого нет ни на одной версии: явный инцидент, не NPE и не тихий latest.
        String childKey = uniq("c8callee");
        processDefinitionService.addProcessDefinition(
            bpmn("test-c8-child-tagged.bpmn")
                .replace("c8-child-tagged", childKey)
                .replace("C8TAG", "v1")
                .replace("C8MARKER", "\"v1\""));
        String key = uniq("c8btv");
        String xml = bpmn("test-c8-binding-version-tag.bpmn")
            .replace("c8-binding-version-tag", key)
            .replace("c8-callee-tagged", childKey)
            .replace("versionTag=\"v1\"", "versionTag=\"nope\"");
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();
        List<IncidentEntity> incidents = incidentsOfInstance(piId);
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getMessage())
            .contains("'call'")
            .contains("versionTag 'nope'")
            .contains("no matching deployed version");
    }

    @Test
    @Transactional
    void bindingTypeVersionTag_missingAttribute_parksInformativeIncident() throws Exception {
        // bindingType="versionTag" без атрибута versionTag: структурная ошибка модели.
        String childKey = uniq("c8callee");
        processDefinitionService.addProcessDefinition(
            bpmn("test-c8-child.bpmn")
                .replace("c8-child", childKey)
                .replace("C8MARKER", "\"v1\""));
        String key = uniq("c8btv");
        String xml = bpmn("test-c8-binding-version-tag.bpmn")
            .replace("c8-binding-version-tag", key)
            .replace("c8-callee-tagged", childKey)
            .replace(" versionTag=\"v1\"", "");
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();
        List<IncidentEntity> incidents = incidentsOfInstance(piId);
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getMessage())
            .contains("'call'")
            .contains("bindingType=\"versionTag\" but no versionTag attribute");
    }

    // ==================== §C.2: decisionId как FEEL (WO-C8-2 GREEN) ====================

    @Test
    @Transactional
    void feelDecisionId_expressionResolvesToDecision() throws Exception {
        // WO-C8-2 GREEN (переписан из feelDecisionId_resolvesLiterally_baselineForWoC8_2).
        String decision = uniq("c8d");
        dmnService.deploy(bpmn("test-c8-pinned-decision.dmn")
            .replace("c8pinned", decision)
            .replace("C8VAL", "\"v1-val\""));
        String tier = decision.substring(decision.lastIndexOf('-') + 1);
        String key = uniq("c8fdid");
        String xml = bpmn("test-c8-feel-decision-id.bpmn")
            .replace("c8-feel-decision-id", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of(var("tier", ProcessVariableType.STRING, tier)));

        ProcessInstance pi = queryService.getProcessInstance(piId);
        assertThat(pi.getCompletedAt()).isNotNull();
        ProcessVariableEntity picked = variableRepository
            .findByNameAndProcessInstanceId("picked", piId).orElseThrow();
        assertThat(picked.getTextValue()).isEqualTo("v1-val");
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    @Test
    @Transactional
    void feelDecisionId_nullResolve_parksInformativeIncident() throws Exception {
        // WO-C8-2, критерий 4: явная ошибка с текстом выражения, не NPE внутри evaluate.
        String decision = uniq("c8d");
        dmnService.deploy(bpmn("test-c8-pinned-decision.dmn")
            .replace("c8pinned", decision)
            .replace("C8VAL", "\"v1-val\""));
        String key = uniq("c8fdid");
        String xml = bpmn("test-c8-feel-decision-id.bpmn")
            .replace("c8-feel-decision-id", key)
            .replace("= &quot;c8d-&quot; + tier", "= null");
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of(var("tier", ProcessVariableType.STRING, "gold")));

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();
        List<IncidentEntity> incidents = incidentsOfInstance(piId);
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getMessage())
            .contains("'decide'")
            .contains("= null")
            .contains("resolved to null/blank");
    }

    // ==================== §C.3: приоритет error boundary ====================

    @Test
    @Transactional
    void errorBoundary_specificWinsRegardlessOfOrder() throws Exception {
        // WO-C8-4 GREEN (переименован из errorBoundary_specificVsCatchAll_firstMatchInIterationWins):
        // брошена E-1 при двух боундари на sub1 (catch-all объявлен в XML ПЕРВЫМ) —
        // побеждает specific-ветка, как в Camunda 8 (Addendum gap-анализа: Zeebe 8.6).
        String key = uniq("c8ep");
        String xml = bpmn("test-c8-error-priority.bpmn").replace("c8-error-priority", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        Set<String> completedEnds = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(piId))
            .filter(a -> a.getStatus() == ActivityStatus.COMPLETED)
            .map(ActivityEntity::getBpmnElementId)
            .collect(Collectors.toSet());
        assertThat(completedEnds).contains("endSpecific");
        assertThat(completedEnds).doesNotContain("endCatchAll");
        assertThat(completedEnds).doesNotContain("endEvent");
    }

    @Test
    @Transactional
    void errorBoundary_specificWinsWhenDeclaredFirst() throws Exception {
        // WO-C8-4, симметричный случай: specific объявлен ПЕРВЫМ, catch-all ВТОРЫМ —
        // результат тот же (endSpecific), что доказывает приоритет, а не переворот порядка.
        String key = uniq("c8epr");
        String xml = bpmn("test-c8-error-priority-reversed.bpmn").replace("c8-error-priority-reversed", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        Set<String> completedEnds = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(piId))
            .filter(a -> a.getStatus() == ActivityStatus.COMPLETED)
            .map(ActivityEntity::getBpmnElementId)
            .collect(Collectors.toSet());
        assertThat(completedEnds).contains("endSpecific");
        assertThat(completedEnds).doesNotContain("endCatchAll");
        assertThat(completedEnds).doesNotContain("endEvent");
    }

    // ==================== §C.3: приоритет escalation boundary ====================

    @Test
    @Transactional
    void escalationBoundary_specificWinsRegardlessOfOrder() throws Exception {
        // WO-C8-4 GREEN (переименован из escalationBoundary_specificVsCatchAll_firstMatchInIterationWins):
        // ESC-1 из дочернего процесса всплывает на call activity с двумя боундари
        // (catch-all первым) — побеждает specific.
        String childKey = uniq("c8escch");
        processDefinitionService.addProcessDefinition(
            bpmn("test-c8-esc-child.bpmn").replace("c8-esc-child", childKey));
        String key = uniq("c8escp");
        String xml = bpmn("test-c8-escalation-parent.bpmn")
            .replace("c8-escalation-parent", key)
            .replace("c8-esc-child", childKey);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        Set<String> completedEnds = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(piId))
            .filter(a -> a.getStatus() == ActivityStatus.COMPLETED)
            .map(ActivityEntity::getBpmnElementId)
            .collect(Collectors.toSet());
        assertThat(completedEnds).contains("endSpecific");
        assertThat(completedEnds).doesNotContain("endCatchAll");
        assertThat(completedEnds).doesNotContain("endEvent");
    }

    @Test
    @Transactional
    void eventSubprocessErrorHandler_specificWinsRegardlessOfOrder() throws Exception {
        // WO-C8-4, место 2: два error-triggered event subprocess (catch-all объявлен ПЕРВЫМ) —
        // брошена E-1, срабатывает specific-хендлер.
        String key = uniq("c8evsp");
        String xml = bpmn("test-c8-event-subprocess-error-priority.bpmn").replace("c8-evsub-priority", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        Set<String> completedEnds = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(piId))
            .filter(a -> a.getStatus() == ActivityStatus.COMPLETED)
            .map(ActivityEntity::getBpmnElementId)
            .collect(Collectors.toSet());
        assertThat(completedEnds).contains("evEndSpecific");
        assertThat(completedEnds).doesNotContain("evEndCatchAll");
    }

    // ==================== НОВОЕ (не было в gap-анализе): дроп inner intermediate-throw ====================

    @Test
    @Transactional
    void intermediateThrowInsideSubprocess_isDroppedByParser() throws Exception {
        // Парсер сабпроцесса собирает только endEvents/service/script/user tasks/gateways/flows
        // (BpmnParseServiceImpl, ветка sub-*): intermediateThrowEvent внутри sub1 дропается,
        // поток упирается в missing target. В gap-анализе этого нет.
        String key = uniq("c8esp");
        String xml = bpmn("test-c8-escalation-priority.bpmn").replace("c8-escalation-priority", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();
        List<IncidentEntity> incidents = incidentsOfInstance(piId);
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getMessage()).contains("escThrow").contains("not found");
    }

    // ==================== §C.2: MI на intermediate throw ====================

    @Test
    @Transactional
    void miOnIntermediateThrow_deploysAndSkipsMultiInstance() throws Exception {
        String key = uniq("c8mit");
        String xml = bpmn("test-c8-mi-throw.bpmn").replace("c8-mi-throw", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of(var("items", ProcessVariableType.STRING, "[1,2,3]")));

        // loopCharacteristics на intermediate throw не исполняется как multi-instance:
        // событие срабатывает один раз, процесс завершается.
        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    // ==================== критерий 4: Complex Gateway ====================

    @Test
    @Transactional
    void complexGateway_isDropped_flowParksOnMissingTarget() throws Exception {
        String key = uniq("c8cg");
        String xml = bpmn("test-c8-complex-gateway.bpmn").replace("c8-complex-gateway", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());

        // <bpmn:complexGateway> не парсится (как и у Zeebe): поток упирается в missing target.
        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();
        List<IncidentEntity> incidents = incidentsOfInstance(piId);
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getMessage()).contains("cg").contains("not found");
    }

    // ==================== критерий 4: Pools/Lanes ====================

    @Test
    @Transactional
    void poolsAndLanes_doNotAffectExecution() throws Exception {
        String key = uniq("c8pool");
        String xml = bpmn("test-c8-pools.bpmn").replace("c8-pools", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of(var("amount", ProcessVariableType.LONG, "5")));

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        ProcessVariableEntity doubled = variableRepository
            .findByNameAndProcessInstanceId("doubled", piId).orElseThrow();
        assertThat(doubled.getTextValue()).isEqualTo("10");
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    // ==================== критерий 4: Data Objects ====================

    @Test
    @Transactional
    void dataObjects_doNotAffectExecution() throws Exception {
        String key = uniq("c8data");
        String xml = bpmn("test-c8-data-objects.bpmn").replace("c8-data-objects", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of(var("amount", ProcessVariableType.LONG, "5")));

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        ProcessVariableEntity doubled = variableRepository
            .findByNameAndProcessInstanceId("doubled", piId).orElseThrow();
        assertThat(doubled.getTextValue()).isEqualTo("10");
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    // ==================== критерий 5: композитная модель ====================

    @Test
    @Transactional
    void compositeModel_bigOrder_completesEndToEnd() throws Exception {
        String key = uniq("c8comp");
        String xml = bpmn("test-c8-composite.bpmn").replace("c8-composite", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of(var("amount", ProcessVariableType.LONG, "500")));

        // service task charge паркуется → завершаем через API.
        runtimeService.completeServiceTask(activity(piId, "charge").getId(), List.of());
        // FEEL-условие amount > 100 ведёт на review; boundary timer PT10M не срабатывает.
        completeUserTask(piId, "review");
        runtimeService.completeServiceTask(activity(piId, "ship").getId(), List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        Set<String> completedEnds = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(piId))
            .filter(a -> a.getStatus() == ActivityStatus.COMPLETED)
            .map(ActivityEntity::getBpmnElementId)
            .collect(Collectors.toSet());
        assertThat(completedEnds).contains("endEvent");
        assertThat(completedEnds).doesNotContain("timedOut");
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    @Test
    @Transactional
    void compositeModel_smallOrder_skipsReview() throws Exception {
        String key = uniq("c8comp");
        String xml = bpmn("test-c8-composite.bpmn").replace("c8-composite", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of(var("amount", ProcessVariableType.LONG, "10")));

        runtimeService.completeServiceTask(activity(piId, "charge").getId(), List.of());
        // amount <= 100: review не посещается, поток идёт charge -> ship напрямую.
        assertThat(activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(piId))
            .noneMatch(a -> a.getBpmnElementId().equals("review"))).isTrue();
        runtimeService.completeServiceTask(activity(piId, "ship").getId(), List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }
}
