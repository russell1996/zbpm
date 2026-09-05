package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.DeploymentDTO;
import com.zorrodev.bpm.contract.dto.DeploymentItemDTO;
import com.zorrodev.bpm.contract.dto.DeploymentResourceType;
import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.bpmn.model.ListenerModel;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.FormEntity;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.entity.OutboxKind;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.entity.TimerJobEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.FormRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.TimerJobRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import com.zorrodev.bpm.engine.scheduler.TimerJobExecutor;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.DeploymentService;
import com.zorrodev.bpm.engine.service.DmnService;
import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import com.zorrodev.bpm.engine.service.impl.ServiceTaskEnqueueServiceImpl;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import com.zorrodev.bpm.engine.handler.ElementSupport;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
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

    @Autowired
    private FormRepository formRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private UserTaskRepository userTaskRepository;

    @Autowired
    private DBService dbService;

    @Autowired
    private ActivityService activityService;

    @Autowired
    private DeploymentService deploymentService;

    @Autowired
    private com.zorrodev.bpm.engine.scheduler.TimerJobExecutor timerJobExecutor;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Autowired
    private TimerJobRepository timerJobRepository;

    @Autowired
    private ProcessInstanceRepository processInstanceRepository;

    /**
     * WO-C8-11: в profile "test" штатный {@code TestServiceTaskEnqueueService} — no-op
     * (в outbox в IT ничего не пишется вообще), поэтому listener-блокировку через настоящий
     * порядок outbox-записей этот класс доказывает настоящим {@code ServiceTaskEnqueueServiceImpl}
     * ({@code @Primary}, только для этого класса — стаб остальных классов сюиты не трогаем).
     * Все коллабораторы — настоящие бины контекста; {@code OutboxPollerService} в тестах выключен,
     * записи инертны и читаются напрямую через {@code OutboxRepository}.
     */
    @TestConfiguration
    static class RealEnqueueTestConfig {
        @Bean
        @Primary
        ServiceTaskEnqueueService realServiceTaskEnqueueService(DBService dbService, BpmnService bpmnService,
                OutboxRepository outboxRepository, tools.jackson.databind.ObjectMapper objectMapper,
                ElementSupport elementSupport) {
            return new ServiceTaskEnqueueServiceImpl(dbService, bpmnService, outboxRepository, objectMapper, elementSupport);
        }
    }

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
    void executionListeners_startListenerBlocksRealJobUntilComplete() throws Exception {
        // WO-C8-11 GREEN (переименован из executionListeners_areSilentlyIgnored_serviceTaskParksWithoutIncident):
        // start-listener РЕАЛЬНО блокирует: в outbox уходит listener-job, job-c8 — только после
        // завершения listener'а; токен двигается только после завершения настоящего job'а.
        // WO-C8-11b: end-listener из общей фикстуры вырезан — этот тест изолирует START-фазу
        // (end-фазу доказывают executionListeners_end* тесты); иначе поток уходил бы в end-фазу.
        String key = uniq("c8el");
        String xml = bpmn("test-c8-execution-listeners.bpmn")
            .replace("c8-exec-listeners", key)
            .replace("          <zeebe:executionListener eventType=\"end\" type=\"listener-job\" />\n", "");

        assertThat(bpmnParseService.parse(xml).getElement("svc").getExtensions()
            .getServiceTaskExtension().getStartListeners())
            .extracting(ListenerModel::jobType)
            .containsExactly("listener-job");

        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());

        UUID activityId = activity(piId, "svc").getId();
        assertThat(activity(piId, "svc").getStatus()).isEqualTo(ActivityStatus.CREATED);
        // listener #0 in flight — real job not dispatched
        assertThat(dbService.getServiceTaskPendingListenerIndex(activityId)).isEqualTo(0);
        assertThat(serviceTaskJobs(piId)).containsExactly("listener-job");

        // complete the listener job through the regular completion path
        runtimeService.completeServiceTask(activityId, List.of());

        // real job dispatched now; token still parked
        assertThat(dbService.getServiceTaskPendingListenerIndex(activityId)).isNull();
        assertThat(serviceTaskJobs(piId)).containsExactly("listener-job", "job-c8");
        assertThat(activity(piId, "svc").getStatus()).isEqualTo(ActivityStatus.CREATED);
        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();

        // complete the real job — token moves, instance completes
        runtimeService.completeServiceTask(activityId, List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    @Test
    @Transactional
    void executionListeners_twoStartListeners_runSequentiallyInDeclarationOrder() throws Exception {
        // WO-C8-11, критерий 3: второй start-listener диспетчеризуется только после завершения
        // первого (порядок outbox-записей + индекс между шагами), затем — настоящий job.
        String key = uniq("c8el2");
        String xml = bpmn("test-c8-execution-listeners-two-starts.bpmn").replace("c8-exec-listeners-2", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());
        UUID activityId = activity(piId, "svc").getId();

        assertThat(serviceTaskJobs(piId)).containsExactly("listener-job-1");
        assertThat(dbService.getServiceTaskPendingListenerIndex(activityId)).isEqualTo(0);

        runtimeService.completeServiceTask(activityId, List.of());

        assertThat(serviceTaskJobs(piId)).containsExactly("listener-job-1", "listener-job-2");
        assertThat(dbService.getServiceTaskPendingListenerIndex(activityId)).isEqualTo(1);

        runtimeService.completeServiceTask(activityId, List.of());

        assertThat(serviceTaskJobs(piId)).containsExactly("listener-job-1", "listener-job-2", "job-c8");
        assertThat(dbService.getServiceTaskPendingListenerIndex(activityId)).isNull();

        runtimeService.completeServiceTask(activityId, List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    @Test
    @Transactional
    void executionListeners_listenerFailureWithZeroRetries_raisesIncidentOnSharedPath() throws Exception {
        // WO-C8-11, критерий 4: failServiceTask(id, msg, 0) на припаркованном listener-job идёт
        // тем же путём, что обычный job (явный retries=0 = семантика Camunda failJob из javadoc
        // failServiceTask) — инцидент, токен стоит, отдельного listener-механизма нет.
        // retries-атрибут XML парсится в ListenerModel, но в этом срезе не применяется (будущий WO).
        String key = uniq("c8elf");
        String xml = bpmn("test-c8-execution-listeners.bpmn").replace("c8-exec-listeners", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());
        UUID activityId = activity(piId, "svc").getId();

        assertThat(serviceTaskJobs(piId)).containsExactly("listener-job");

        runtimeService.failServiceTask(activityId, "listener boom", 0);

        assertThat(incidentsOfInstance(piId)).hasSize(1);
        assertThat(activity(piId, "svc").getStatus()).isEqualTo(ActivityStatus.ERROR);
        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();
        // exhausted budget — no redispatch
        assertThat(serviceTaskJobs(piId)).containsExactly("listener-job");
    }

    // ==================== WO-C8-11b (A-2 срез 2): end execution listeners ====================

    @Test
    @Transactional
    void executionListeners_endListenerBlocksTokenAdvance() throws Exception {
        // WO-C8-11b, критерии 2+5: end-listener РЕАЛЬНО блокирует — реальный job завершён,
        // но токен стоит, а активность НЕ COMPLETED (прямой ассерт п.9: блокер снят правильно,
        // а не обойдён); end-listener диспетчеризован; после его завершения — продвижение.
        String key = uniq("c8ele");
        String xml = bpmn("test-c8-execution-listeners-end-only.bpmn").replace("c8-exec-listeners-end", key);

        assertThat(bpmnParseService.parse(xml).getElement("svc").getExtensions()
            .getServiceTaskExtension().getEndListeners())
            .extracting(ListenerModel::jobType)
            .containsExactly("listener-job-end");

        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());
        UUID activityId = activity(piId, "svc").getId();

        // real job dispatched first, activity parked
        assertThat(serviceTaskJobs(piId)).containsExactly("job-c8");
        assertThat(activity(piId, "svc").getStatus()).isEqualTo(ActivityStatus.CREATED);

        // complete the real job — token must NOT advance, activity must NOT complete
        runtimeService.completeServiceTask(activityId, List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();
        assertThat(activity(piId, "svc").getStatus()).isEqualTo(ActivityStatus.CREATED);
        assertThat(dbService.getServiceTaskPendingEndListenerIndex(activityId)).isEqualTo(0);
        assertThat(serviceTaskJobs(piId)).containsExactly("job-c8", "listener-job-end");

        // complete the end listener — now the token advances
        runtimeService.completeServiceTask(activityId, List.of());

        assertThat(activity(piId, "svc").getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        assertThat(dbService.getServiceTaskPendingEndListenerIndex(activityId)).isNull();
        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    @Test
    @Transactional
    void executionListeners_twoEndListeners_runSequentiallyInDeclarationOrder() throws Exception {
        // WO-C8-11b, критерий 3 (зеркало twoStartListeners): второй end-listener
        // диспетчеризуется только после завершения первого; индекс 0→1→null.
        String key = uniq("c8el2e");
        String xml = bpmn("test-c8-execution-listeners-two-ends.bpmn").replace("c8-exec-listeners-two-ends", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());
        UUID activityId = activity(piId, "svc").getId();

        assertThat(serviceTaskJobs(piId)).containsExactly("job-c8");

        runtimeService.completeServiceTask(activityId, List.of());

        assertThat(serviceTaskJobs(piId)).containsExactly("job-c8", "listener-job-end-1");
        assertThat(dbService.getServiceTaskPendingEndListenerIndex(activityId)).isEqualTo(0);

        runtimeService.completeServiceTask(activityId, List.of());

        assertThat(serviceTaskJobs(piId)).containsExactly("job-c8", "listener-job-end-1", "listener-job-end-2");
        assertThat(dbService.getServiceTaskPendingEndListenerIndex(activityId)).isEqualTo(1);

        runtimeService.completeServiceTask(activityId, List.of());

        assertThat(dbService.getServiceTaskPendingEndListenerIndex(activityId)).isNull();
        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    @Test
    @Transactional
    void executionListeners_startAndEnd_fullChainInOrder() throws Exception {
        // WO-C8-11b, критерий 4 (главный интеграционный тест): start + end на одном элементе —
        // listener-start → job-c8 → listener-end → продвижение.
        String key = uniq("c8else");
        String xml = bpmn("test-c8-execution-listeners-start-end.bpmn").replace("c8-exec-listeners-start-end", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());
        UUID activityId = activity(piId, "svc").getId();

        assertThat(serviceTaskJobs(piId)).containsExactly("listener-job-start");
        assertThat(dbService.getServiceTaskPendingListenerIndex(activityId)).isEqualTo(0);

        runtimeService.completeServiceTask(activityId, List.of());

        assertThat(serviceTaskJobs(piId)).containsExactly("listener-job-start", "job-c8");
        assertThat(dbService.getServiceTaskPendingListenerIndex(activityId)).isNull();
        assertThat(activity(piId, "svc").getStatus()).isEqualTo(ActivityStatus.CREATED);

        runtimeService.completeServiceTask(activityId, List.of());

        assertThat(serviceTaskJobs(piId)).containsExactly("listener-job-start", "job-c8", "listener-job-end");
        assertThat(dbService.getServiceTaskPendingEndListenerIndex(activityId)).isEqualTo(0);
        assertThat(activity(piId, "svc").getStatus()).isEqualTo(ActivityStatus.CREATED);

        runtimeService.completeServiceTask(activityId, List.of());

        assertThat(dbService.getServiceTaskPendingEndListenerIndex(activityId)).isNull();
        assertThat(activity(piId, "svc").getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    /** WO-C8-11: job types of THIS test's outbox SERVICE_TASK entries, oldest first. */
    private List<String> serviceTaskJobs(UUID processInstanceId) throws Exception {
        tools.jackson.databind.ObjectMapper om = new tools.jackson.databind.ObjectMapper();
        List<String> jobs = new java.util.ArrayList<>();
        // Filter by the payload's processInstanceId: the outbox table is global and other
        // test classes commit rows from worker threads (no rollback across thread boundaries),
        // so an unfiltered findAll() sees foreign jobs (e.g. "flaky" from incident-resolve tests).
        List<OutboxEntry> entries = outboxRepository.findAll().stream()
            .filter(e -> e.getKind() == OutboxKind.SERVICE_TASK)
            .filter(e -> {
                try {
                    return processInstanceId.toString().equals(om.readTree(e.getPayload()).get("processInstanceId").asText());
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            })
            .sorted(java.util.Comparator.comparing(OutboxEntry::getCreatedAt).thenComparing(OutboxEntry::getId))
            .collect(Collectors.toList());
        for (OutboxEntry e : entries) {
            jobs.add(om.readTree(e.getPayload()).get("job").asText());
        }
        return jobs;
    }

    /** WO-C8-14b: runs the action in its own committed transaction (timer fires need committed rows). */
    private void inNewTx(Runnable action) {
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tt.execute(status -> {
            action.run();
            return null;
        });
    }

    /** WO-C8-14b: fires due timer jobs created after {@code since} (foreign jobs untouched). */
    private void fireDueTimersInWindow(Instant since) {
        dbService.findDueTimerJobs(Instant.now().plusSeconds(3600)).stream()
            .filter(j -> j.getCreatedAt() != null && !j.getCreatedAt().isBefore(since))
            .forEach(j -> {
                try {
                    timerJobExecutor.fire(j);
                } catch (Exception ignored) {
                    // isolate unrelated jobs, mirroring TimerScheduler
                }
            });
    }

    /** WO-C8-14b: deletes timer jobs created after {@code since} (committed-tx test cleanup). */
    private void deleteTimerJobsInWindow(Instant since) {
        List<TimerJobEntity> mine = timerJobRepository.findAll().stream()
            .filter(e -> e.getCreatedAt() != null && !e.getCreatedAt().isBefore(since))
            .toList();
        timerJobRepository.deleteAll(mine);
    }

    // ==================== §C.1: zeebe:taskListeners ====================

    /** WO-C8-21: видимые (созданные) задачи инстанса — прямой ассерт блокирующей семантики. */
    private List<UserTask> userTasksOfInstance(UUID processInstanceId) {
        UserTaskQuery query = new UserTaskQuery();
        query.setProcessInstanceId(processInstanceId);
        return queryService.findUserTasks(query, null).getData();
    }

    @Test
    @Transactional
    void taskListeners_creatingListenerBlocksTaskUntilComplete() throws Exception {
        // WO-C8-21 GREEN (переименован из taskListeners_doNotBreakUserTaskFlow (WO-C8-1)):
        // фикстура Фазы 0 несла невалидный eventType="create" (такого события нет ни в схеме,
        // ни в доке — находка №4 класса ФИКСТУРА≠ИСТИНА) — исправлен на "creating", и тест
        // теперь доказывает блокирующую семантику вместо "не ломает поток".
        String key = uniq("c8tl");
        String xml = bpmn("test-c8-task-listeners.bpmn").replace("c8-task-listeners", key);

        assertThat(bpmnParseService.parse(xml).getElement("review").getExtensions()
            .getUserTaskExtension().getCreatingListeners())
            .extracting(ListenerModel::jobType)
            .containsExactly("notify-job");

        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());

        UUID activityId = activity(piId, "review").getId();
        assertThat(activity(piId, "review").getStatus()).isEqualTo(ActivityStatus.CREATED);
        // creating listener #0 in flight — задача НЕ создана: в списке её нет, и в user_tasks
        // нет даже строки (раунд 2: индекс фазы живёт на активности, маркера больше нет)
        assertThat(dbService.getPendingCreatingListenerIndex(activityId)).isEqualTo(0);
        assertThat(serviceTaskJobs(piId)).containsExactly("notify-job");
        assertThat(userTasksOfInstance(piId)).isEmpty();
        assertThat(userTaskRepository.findById(activityId)).isEmpty();

        // complete the listener job through the regular completion path
        runtimeService.completeServiceTask(activityId, List.of());

        // task created now, no follow-up job (у user task нет "настоящего" job'а); instance parked
        assertThat(dbService.getPendingCreatingListenerIndex(activityId)).isNull();
        assertThat(serviceTaskJobs(piId)).containsExactly("notify-job");
        assertThat(userTasksOfInstance(piId)).hasSize(1);
        assertThat(activity(piId, "review").getStatus()).isEqualTo(ActivityStatus.CREATED);
        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();

        // complete the user task — token moves, instance completes
        completeUserTask(piId, "review");

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    @Test
    @Transactional
    void taskListeners_twoCreatingListeners_runSequentiallyInDeclarationOrder() throws Exception {
        // WO-C8-21, критерий 3 (зеркало executionListeners_twoStartListeners): второй
        // creating-listener диспетчеризуется только после завершения первого; висящий между
        // ними assigning-listener парсер обязан пропустить (чужие WO), порядок не рвётся.
        String key = uniq("c8tl2");
        String xml = bpmn("test-c8-task-listeners-two-creating.bpmn").replace("c8-task-listeners-2", key);

        assertThat(bpmnParseService.parse(xml).getElement("review").getExtensions()
            .getUserTaskExtension().getCreatingListeners())
            .extracting(ListenerModel::jobType)
            .containsExactly("creating-job-1", "creating-job-2");

        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());
        UUID activityId = activity(piId, "review").getId();

        assertThat(serviceTaskJobs(piId)).containsExactly("creating-job-1");
        assertThat(dbService.getPendingCreatingListenerIndex(activityId)).isEqualTo(0);
        assertThat(userTasksOfInstance(piId)).isEmpty();

        runtimeService.completeServiceTask(activityId, List.of());

        assertThat(serviceTaskJobs(piId)).containsExactly("creating-job-1", "creating-job-2");
        assertThat(dbService.getPendingCreatingListenerIndex(activityId)).isEqualTo(1);
        assertThat(userTasksOfInstance(piId)).isEmpty();

        runtimeService.completeServiceTask(activityId, List.of());

        assertThat(serviceTaskJobs(piId)).containsExactly("creating-job-1", "creating-job-2");
        assertThat(dbService.getPendingCreatingListenerIndex(activityId)).isNull();
        assertThat(userTasksOfInstance(piId)).hasSize(1);

        completeUserTask(piId, "review");

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    @Test
    @Transactional
    void taskListeners_taskNotVisibleWhileCreatingPhaseRuns() throws Exception {
        // WO-C8-21, критерий 4 (раунд 2: маркера больше нет — отсутствие ВЕЗДЕ следует из
        // отсутствия строки): задача середины фазы отсутствует в списке, по id (тот же 404,
        // что у несуществующей) и для claim'а (строки нет — orElseThrow); фаза при этом идёт
        // (индекс на активности). После фазы — видна и по списку, и по id.
        String key = uniq("c8tlv");
        String xml = bpmn("test-c8-task-listeners.bpmn").replace("c8-task-listeners", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());
        UUID activityId = activity(piId, "review").getId();

        assertThat(userTasksOfInstance(piId)).isEmpty();
        assertThatThrownBy(() -> queryService.getUserTask(activityId))
            .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> dbService.claimUserTask(activityId, "bob"))
            .isInstanceOf(NoSuchElementException.class);
        assertThat(userTaskRepository.findById(activityId)).isEmpty();
        assertThat(dbService.getPendingCreatingListenerIndex(activityId)).isEqualTo(0);

        runtimeService.completeServiceTask(activityId, List.of());

        assertThat(userTasksOfInstance(piId)).hasSize(1);
        assertThat(queryService.getUserTask(activityId).getId()).isEqualTo(activityId);
    }

    @Test
    @Transactional
    void taskListeners_corruptCreatingIndex_failsOpenIntoTaskCreation() throws Exception {
        // WO-C8-21, шаг 6 (зеркало C8-11 fail-open): битый индекс (модель redeployed
        // mid-flight) — задача создаётся, а не strand'ится; инцидента нет.
        String key = uniq("c8tlf");
        String xml = bpmn("test-c8-task-listeners.bpmn").replace("c8-task-listeners", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());
        UUID activityId = activity(piId, "review").getId();

        dbService.setPendingCreatingListenerIndex(activityId, 99);

        runtimeService.completeServiceTask(activityId, List.of());

        assertThat(userTasksOfInstance(piId)).hasSize(1);
        assertThat(dbService.getPendingCreatingListenerIndex(activityId)).isNull();
        assertThat(incidentsOfInstance(piId)).isEmpty();

        completeUserTask(piId, "review");

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
    }

    @Test
    @Transactional
    void taskListeners_creatingListenerFailure_raisesIncident() throws Exception {
        // WO-C8-21 (hardening сверх критериев, зеркало C8-11 критерия 4): упавший
        // creating-listener job паркует токен с инцидентом общим путём (отдельного
        // listener-механизма нет); задача по-прежнему не создана, redispatch нет.
        String key = uniq("c8tle");
        String xml = bpmn("test-c8-task-listeners.bpmn").replace("c8-task-listeners", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());
        UUID activityId = activity(piId, "review").getId();

        assertThat(serviceTaskJobs(piId)).containsExactly("notify-job");

        runtimeService.failServiceTask(activityId, "listener boom", 0);

        assertThat(incidentsOfInstance(piId)).hasSize(1);
        assertThat(activity(piId, "review").getStatus()).isEqualTo(ActivityStatus.ERROR);
        assertThat(userTasksOfInstance(piId)).isEmpty();
        // exhausted — no redispatch
        assertThat(serviceTaskJobs(piId)).containsExactly("notify-job");
    }

    // ==================== WO-C8-21 раунд 2: фаза вне user_tasks + retries ====================

    @Test
    @Transactional
    void creatingPhase_userTasksTableEmptyMidPhase() throws Exception {
        // Раунд 2, крит. 1 (п.8): в середине фазы в user_tasks НЕТ СТРОКИ вообще — прямой
        // ассерт на repository/БД (findById + findAll по инстансу), а не на выдачу API.
        // Именно отсутствие этого теста позволило дефекту раунда 1 появиться.
        String key = uniq("c8r2e");
        String xml = bpmn("test-c8-task-listeners.bpmn").replace("c8-task-listeners", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());
        UUID activityId = activity(piId, "review").getId();

        // фаза идёт (индекс на активности), а задачи нет ни по id, ни в таблице
        assertThat(dbService.getPendingCreatingListenerIndex(activityId)).isEqualTo(0);
        assertThat(userTaskRepository.findById(activityId)).isEmpty();
        assertThat(userTaskRepository.findByProcessInstanceId(piId)).isEmpty();

        runtimeService.completeServiceTask(activityId, List.of());

        // фаза закрыта — строка ровно одна
        assertThat(dbService.getPendingCreatingListenerIndex(activityId)).isNull();
        assertThat(userTaskRepository.findById(activityId)).isPresent();
        assertThat(userTaskRepository.findByProcessInstanceId(piId)).hasSize(1);
    }

    @Test
    @Transactional
    void creatingPhase_completeMidPhase_notFound() throws Exception {
        // Раунд 2, крит. 2 (п.7): complete недосозданной задачи — 404-класс
        // (NoSuchElementException → REST 404), а не 500-класс. На master (раунд 1):
        // строка-маркер есть → requireCreated → IllegalStateException → RED.
        String key = uniq("c8r2c");
        String xml = bpmn("test-c8-task-listeners.bpmn").replace("c8-task-listeners", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());
        UUID activityId = activity(piId, "review").getId();

        assertThatThrownBy(() -> runtimeService.completeUserTask(activityId, List.of()))
            .isInstanceOf(NoSuchElementException.class);
        // Порядок побочных эффектов — доисковой (completeActivity до проверки строки):
        // activity помечена COMPLETED, но токен стоит (outgoing не идётся), инцидентов нет.
        // В проде через REST сюда не дойти — там 404 раньше, на findById.
        assertThat(activity(piId, "review").getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        assertThat(incidentsOfInstance(piId)).isEmpty();
        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();
    }

    @Test
    @Transactional
    void creatingListener_retriesFromModel_incidentAfterSecondAttempt() throws Exception {
        // Раунд 2, крит. 4 (п.9): retries="2" — первое падение редиспатчит без инцидента,
        // второе паркует с инцидентом.
        String key = uniq("c8r2r");
        String xml = bpmn("test-c8-task-listeners.bpmn").replace("c8-task-listeners", key)
            .replace("type=\"notify-job\"", "type=\"notify-job\" retries=\"2\"");
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());
        UUID activityId = activity(piId, "review").getId();

        assertThat(serviceTaskJobs(piId)).containsExactly("notify-job");

        runtimeService.failServiceTask(activityId, "listener boom", null);
        assertThat(incidentsOfInstance(piId)).isEmpty();
        assertThat(serviceTaskJobs(piId)).containsExactly("notify-job", "notify-job");

        runtimeService.failServiceTask(activityId, "listener boom", null);
        assertThat(incidentsOfInstance(piId)).hasSize(1);
        assertThat(activity(piId, "review").getStatus()).isEqualTo(ActivityStatus.ERROR);
    }

    @Test
    @Transactional
    void creatingListener_noRetriesAttribute_defaultsToThreeAttempts() throws Exception {
        // Раунд 2, крит. 4: без атрибута — дефолт 3 из доки (инцидент на третьей попытке).
        String key = uniq("c8r2d");
        String xml = bpmn("test-c8-task-listeners.bpmn").replace("c8-task-listeners", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());
        UUID activityId = activity(piId, "review").getId();

        runtimeService.failServiceTask(activityId, "listener boom", null);
        runtimeService.failServiceTask(activityId, "listener boom", null);
        assertThat(incidentsOfInstance(piId)).isEmpty();
        assertThat(serviceTaskJobs(piId)).containsExactly("notify-job", "notify-job", "notify-job");

        runtimeService.failServiceTask(activityId, "listener boom", null);
        assertThat(incidentsOfInstance(piId)).hasSize(1);
        assertThat(activity(piId, "review").getStatus()).isEqualTo(ActivityStatus.ERROR);
    }

    @Test
    @Transactional
    void startListener_retriesFromModel_incidentAfterSecondAttempt() throws Exception {
        // Раунд 2, крит. 4: тот же контракт для start-listener'а (сегодня бюджет общий с
        // настоящим job'ом, retries="2" молча игнорируется).
        String key = uniq("c8r2s");
        String xml = bpmn("test-c8-execution-listeners.bpmn")
            .replace("c8-exec-listeners", key)
            .replace("          <zeebe:executionListener eventType=\"end\" type=\"listener-job\" />\n", "")
            .replace("type=\"listener-job\"", "type=\"listener-job\" retries=\"2\"");
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());
        UUID activityId = activity(piId, "svc").getId();

        assertThat(serviceTaskJobs(piId)).containsExactly("listener-job");

        runtimeService.failServiceTask(activityId, "listener boom", null);
        assertThat(incidentsOfInstance(piId)).isEmpty();
        assertThat(serviceTaskJobs(piId)).containsExactly("listener-job", "listener-job");

        runtimeService.failServiceTask(activityId, "listener boom", null);
        assertThat(incidentsOfInstance(piId)).hasSize(1);
        assertThat(activity(piId, "svc").getStatus()).isEqualTo(ActivityStatus.ERROR);
    }

    @Test
    @Transactional
    void endListener_retriesFromModel_incidentAfterSecondAttempt() throws Exception {
        // Раунд 2, крит. 4: тот же контракт для end-listener'а (единообразие трёх видов —
        // WO запрещает делать молча половину).
        String key = uniq("c8r2n");
        String xml = bpmn("test-c8-execution-listeners-start-end.bpmn")
            .replace("c8-exec-listeners-start-end", key)
            .replace("eventType=\"end\" type=\"listener-job-end\"",
                "eventType=\"end\" type=\"listener-job-end\" retries=\"2\"");
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());
        UUID activityId = activity(piId, "svc").getId();

        // настоящий job + открытие end-фазы
        assertThat(serviceTaskJobs(piId)).containsExactly("listener-job-start");
        runtimeService.completeServiceTask(activityId, List.of());
        assertThat(serviceTaskJobs(piId)).containsExactly("listener-job-start", "job-c8");
        runtimeService.completeServiceTask(activityId, List.of());
        assertThat(serviceTaskJobs(piId))
            .containsExactly("listener-job-start", "job-c8", "listener-job-end");

        runtimeService.failServiceTask(activityId, "listener boom", null);
        assertThat(incidentsOfInstance(piId)).isEmpty();

        runtimeService.failServiceTask(activityId, "listener boom", null);
        assertThat(incidentsOfInstance(piId)).hasSize(1);
        assertThat(activity(piId, "svc").getStatus()).isEqualTo(ActivityStatus.ERROR);
    }

    // ==================== §C.1: zeebe:jobPriorityDefinition (WO-C8-13/A-1: исправлено с priorityDefinition) ====================

    @Test
    @Transactional
    void jobPriorityDefinition_parsedAndDelivered_serviceTaskParksWithoutIncident() throws Exception {
        // WO-C8-13 GREEN (переименован из priorityDefinition_parsedAndDelivered_... (WO-C8-9),
        // тот — из priorityDefinition_isSilentlyIgnored_... (WO-C8-1)): элемент исправлен на
        // zeebe:jobPriorityDefinition — priorityDefinition разрешён схемой только на user task.
        // Доставку до JobDetailModel/outbox доказывает ServiceTaskEnqueueServiceImplTest.
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
    void jobPriorityDefinition_feelExpression_resolvesToInteger() throws Exception {
        // WO-C8-9, критерий 1 (FEEL-форма, имя элемента исправлено WO-C8-13): `=priorityVar`
        // вычисляется через elementSupport.resolvePriority в Integer на реальной переменной.
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
    void jobPriorityDefinition_brokenValue_resolvesToNullWithoutIncident() throws Exception {
        // WO-C8-9, критерий 4 (имя элемента исправлено WO-C8-13): не-Integer — не исключение,
        // не инцидент: резолвится в null, задача паркуется штатно.
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

    @Test
    @Transactional
    void jobPriorityDefinition_processDefault_resolvesWhenTaskHasNone() throws Exception {
        // WO-C8-13, критерий 3: jobPriorityDefinition на <bpmn:process> — default для всех
        // service tasks процесса, у которых своего нет.
        String key = uniq("c8prd");
        String xml = bpmn("test-c8-job-priority-process-default.bpmn").replace("c8-priority-default", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        assertThat(bpmnParseService.parse(xml).getDefaultJobPriority()).isEqualTo("50");

        UUID piId = start(model.getId(), List.of());

        assertThat(elementSupport.resolvePriority(piId, bpmnParseService.parse(xml).getElement("svc")))
            .isEqualTo(50);

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();
        assertThat(activity(piId, "svc").getStatus()).isEqualTo(ActivityStatus.CREATED);
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    @Test
    @Transactional
    void jobPriorityDefinition_taskValue_overridesProcessDefault() throws Exception {
        // WO-C8-13, критерий 4: значение на задаче побеждает process-level default.
        String key = uniq("c8pro");
        String xml = bpmn("test-c8-job-priority-process-default.bpmn")
            .replace("c8-priority-default", key)
            .replace("<zeebe:taskDefinition type=\"job-c8\" />",
                "<zeebe:taskDefinition type=\"job-c8\" /><zeebe:jobPriorityDefinition priority=\"90\" />");
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());

        assertThat(elementSupport.resolvePriority(piId, bpmnParseService.parse(xml).getElement("svc")))
            .isEqualTo(90);

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
    void inlineUserTaskForm_storedAndResolved_deployAndExecute() throws Exception {
        // WO-C8-12 GREEN (переименован из inlineUserTaskForm_doesNotBreakDeployOrExecution):
        // фикстура поправлена (userTaskForm camelCase, внутри process extensionElements, formKey
        // camunda-forms:bpmn:...); embedded-схема реально сохранена в FormEntity и резолвится
        // по formKey — до этого по этому formKey было нечего найти.
        String key = uniq("c8utf");
        String xml = bpmn("test-c8-user-task-form.bpmn").replace("c8-user-task-form", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        FormEntity stored = formRepository
            .findTopByFormKeyOrderByVersionDesc("camunda-forms:bpmn:userTaskForm_order-form")
            .orElseThrow();
        assertThat(stored.getSchemaJson()).contains("\"components\"");
        assertThat(stored.getVersion()).isEqualTo(1);

        UUID piId = start(model.getId(), List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();
        completeUserTask(piId, "review");

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    @Test
    @Transactional
    void inlineUserTaskForm_redeploy_createsNewVersion() throws Exception {
        // WO-C8-12, критерий 3: повторный деплой той же формы создаёт version=2, не
        // перезаписывает version=1 (та же семантика, что ручная загрузка через REST).
        // sha256-идемпотентность деплоя требует изменить XML для новой версии процесса —
        // меняем имя процесса, блок формы байт-в-байт тот же.
        String key = uniq("c8utfr");
        String xml1 = bpmn("test-c8-user-task-form.bpmn").replace("c8-user-task-form", key);
        processDefinitionService.addProcessDefinition(xml1);
        String xml2 = xml1.replace("name=\"" + key + "\"", "name=\"" + key + "-v2\"");
        processDefinitionService.addProcessDefinition(xml2);

        assertThat(formRepository
            .findByFormKeyAndVersion("camunda-forms:bpmn:userTaskForm_order-form", 1))
            .isPresent();
        FormEntity v2 = formRepository
            .findByFormKeyAndVersion("camunda-forms:bpmn:userTaskForm_order-form", 2)
            .orElseThrow();
        assertThat(v2.getSchemaJson()).contains("\"components\"");
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
    void calledDecisionBindingTypeDeployment_pinnedVersionWins() throws Exception {
        // WO-C8-17 GREEN (переименован из calledDecisionBindingType_isIgnored_latestDecisionWins):
        // процесс деплоится первым; v1 привязывается к его версии; прилетевшая позже v2
        // (без привязки) НЕ побеждает — исполняется v1.
        String decision = uniq("c8pinned");
        String key = uniq("c8cdb");
        String xml = bpmn("test-c8-called-decision-binding.bpmn")
            .replace("c8-called-decision-binding", key)
            .replace("c8pinned", decision);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        dmnService.deploy(bpmn("test-c8-pinned-decision.dmn")
            .replace("c8pinned", decision)
            .replace("C8VAL", "\"v1-val\""), model.getId());
        dmnService.deploy(bpmn("test-c8-pinned-decision.dmn")
            .replace("c8pinned", decision)
            .replace("C8VAL", "\"v2-val\""));

        UUID piId = start(model.getId(), List.of(var("tier", ProcessVariableType.STRING, "gold")));

        ProcessInstance pi = queryService.getProcessInstance(piId);
        assertThat(pi.getCompletedAt()).isNotNull();
        ProcessVariableEntity picked = variableRepository
            .findByNameAndProcessInstanceId("picked", piId).orElseThrow();
        assertThat(picked.getTextValue()).isEqualTo("v1-val");
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    @Test
    @Transactional
    void calledDecisionBindingTypeLatest_stillWins() throws Exception {
        // WO-C8-17, критерий 3: bindingType="latest" — прежний путь, побеждает v2.
        String decision = uniq("c8pinned");
        dmnService.deploy(bpmn("test-c8-pinned-decision.dmn")
            .replace("c8pinned", decision)
            .replace("C8VAL", "\"v1-val\""));
        String key = uniq("c8cdl");
        String xml = bpmn("test-c8-called-decision-binding.bpmn")
            .replace("c8-called-decision-binding", key)
            .replace("c8pinned", decision)
            .replace(" bindingType=\"deployment\"", " bindingType=\"latest\"");
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);
        dmnService.deploy(bpmn("test-c8-pinned-decision.dmn")
            .replace("c8pinned", decision)
            .replace("C8VAL", "\"v2-val\""));

        UUID piId = start(model.getId(), List.of(var("tier", ProcessVariableType.STRING, "gold")));

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        ProcessVariableEntity picked = variableRepository
            .findByNameAndProcessInstanceId("picked", piId).orElseThrow();
        assertThat(picked.getTextValue()).isEqualTo("v2-val");
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    @Test
    @Transactional
    void calledDecisionBindingTypeAbsent_stillWins() throws Exception {
        // WO-C8-17, критерий 3: отсутствие атрибута — прежний путь (latest), побеждает v2.
        String decision = uniq("c8pinned");
        dmnService.deploy(bpmn("test-c8-pinned-decision.dmn")
            .replace("c8pinned", decision)
            .replace("C8VAL", "\"v1-val\""));
        String key = uniq("c8cda");
        String xml = bpmn("test-c8-called-decision-binding.bpmn")
            .replace("c8-called-decision-binding", key)
            .replace("c8pinned", decision)
            .replace(" bindingType=\"deployment\"", "");
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);
        dmnService.deploy(bpmn("test-c8-pinned-decision.dmn")
            .replace("c8pinned", decision)
            .replace("C8VAL", "\"v2-val\""));

        UUID piId = start(model.getId(), List.of(var("tier", ProcessVariableType.STRING, "gold")));

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        ProcessVariableEntity picked = variableRepository
            .findByNameAndProcessInstanceId("picked", piId).orElseThrow();
        assertThat(picked.getTextValue()).isEqualTo("v2-val");
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    @Test
    @Transactional
    void calledDecisionBindingTypeDeployment_withoutPinnedVersion_raisesIncident() throws Exception {
        // WO-C8-17, критерий 4: решение деплоилось только без привязки — явный инцидент
        // с внятным текстом, НЕ тихий fallback в latest (иначе чинимый дефект вернулся бы).
        String decision = uniq("c8pinned");
        dmnService.deploy(bpmn("test-c8-pinned-decision.dmn")
            .replace("c8pinned", decision)
            .replace("C8VAL", "\"v1-val\""));
        String key = uniq("c8cdn");
        String xml = bpmn("test-c8-called-decision-binding.bpmn")
            .replace("c8-called-decision-binding", key)
            .replace("c8pinned", decision);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of(var("tier", ProcessVariableType.STRING, "gold")));

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();
        List<IncidentEntity> incidents = incidentsOfInstance(piId);
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getMessage()).contains(decision).contains("bindingType=\"deployment\"");
    }

    // ==================== WO-C8-20: bindingType="versionTag" для calledDecision ====================

    @Test
    @Transactional
    void calledDecisionBindingTypeVersionTag_pinnedTaggedVersionWins() throws Exception {
        // WO-C8-20 GREEN: v1 помечена тегом v1.0, прилетевшая позже v2 — без тега;
        // bindingType="versionTag" исполняет v1, а не latest.
        String decision = uniq("c8tagged");
        dmnService.deploy(bpmn("test-c8-version-tag-decision.dmn")
            .replace("c8tagged", decision)
            .replace("VTAG", "v1.0")
            .replace("C8VAL", "\"v1-val\""));
        String key = uniq("c8cdvt");
        String xml = bpmn("test-c8-called-decision-version-tag.bpmn")
            .replace("c8-called-decision-version-tag", key)
            .replace("c8tagged", decision);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);
        // v2 свежее, но без тега — под пиннинг не попадает.
        dmnService.deploy(bpmn("test-c8-version-tag-decision.dmn")
            .replace("c8tagged", decision)
            .replace("<zeebe:versionTag value=\"VTAG\" />", "")
            .replace("C8VAL", "\"v2-val\""));

        UUID piId = start(model.getId(), List.of(var("tier", ProcessVariableType.STRING, "gold")));

        ProcessInstance pi = queryService.getProcessInstance(piId);
        assertThat(pi.getCompletedAt()).isNotNull();
        ProcessVariableEntity picked = variableRepository
            .findByNameAndProcessInstanceId("picked", piId).orElseThrow();
        assertThat(picked.getTextValue()).isEqualTo("v1-val");
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    @Test
    @Transactional
    void calledDecisionBindingTypeVersionTag_latestTaggedVersionWins() throws Exception {
        // WO-C8-20, критерий 3: тег стоит на НЕСКОЛЬКИХ версиях — берётся ПОСЛЕДНЯЯ из них
        // (доказано прогоном, не чтением запроса).
        String decision = uniq("c8tagged");
        dmnService.deploy(bpmn("test-c8-version-tag-decision.dmn")
            .replace("c8tagged", decision)
            .replace("VTAG", "v1.0")
            .replace("C8VAL", "\"v1-val\""));
        String key = uniq("c8cdvtm");
        String xml = bpmn("test-c8-called-decision-version-tag.bpmn")
            .replace("c8-called-decision-version-tag", key)
            .replace("c8tagged", decision);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);
        dmnService.deploy(bpmn("test-c8-version-tag-decision.dmn")
            .replace("c8tagged", decision)
            .replace("VTAG", "v1.0")
            .replace("C8VAL", "\"v2-val\""));

        UUID piId = start(model.getId(), List.of(var("tier", ProcessVariableType.STRING, "gold")));

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        ProcessVariableEntity picked = variableRepository
            .findByNameAndProcessInstanceId("picked", piId).orElseThrow();
        assertThat(picked.getTextValue()).isEqualTo("v2-val");
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    @Test
    @Transactional
    void calledDecisionBindingTypeVersionTag_missingPair_raisesIncident() throws Exception {
        // WO-C8-20, критерий 4: пары «решение + тег» нет — явный инцидент с внятным текстом,
        // инстанс стоит, НЕ тихий latest.
        String decision = uniq("c8tagged");
        dmnService.deploy(bpmn("test-c8-version-tag-decision.dmn")
            .replace("c8tagged", decision)
            .replace("<zeebe:versionTag value=\"VTAG\" />", "")
            .replace("C8VAL", "\"v1-val\""));
        String key = uniq("c8cdvn");
        String xml = bpmn("test-c8-called-decision-version-tag.bpmn")
            .replace("c8-called-decision-version-tag", key)
            .replace("c8tagged", decision);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of(var("tier", ProcessVariableType.STRING, "gold")));

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();
        List<IncidentEntity> incidents = incidentsOfInstance(piId);
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getMessage()).contains(decision).contains("v1.0");
    }

    @Test
    @Transactional
    void calledDecisionBindingTypeVersionTag_missingTagAttribute_raisesIncident() throws Exception {
        // WO-C8-20: bindingType="versionTag" без атрибута versionTag — явная ошибка с именем
        // элемента (зеркало ветки versionTag CallActivityHandler'а), не silent-null lookup.
        String decision = uniq("c8tagged");
        dmnService.deploy(bpmn("test-c8-version-tag-decision.dmn")
            .replace("c8tagged", decision)
            .replace("VTAG", "v1.0")
            .replace("C8VAL", "\"v1-val\""));
        String key = uniq("c8cdva");
        String xml = bpmn("test-c8-called-decision-version-tag.bpmn")
            .replace("c8-called-decision-version-tag", key)
            .replace("c8tagged", decision)
            .replace(" versionTag=\"v1.0\"", "");
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of(var("tier", ProcessVariableType.STRING, "gold")));

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();
        List<IncidentEntity> incidents = incidentsOfInstance(piId);
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getMessage()).contains("decide").contains("versionTag");
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
    void bindingTypeDeployment_callsPinnedVersionNotLatest() throws Exception {
        // WO-C8-3b GREEN (переименован из bindingTypeDeployment_resolvesLatestNotPinned):
        // родитель и child v1 выложены ОДНИМ деплойментом; прилетевшая позже child v2
        // (отдельно) НЕ вызывается — исполняется зафиксированная v1.
        String childKey = uniq("c8callee");
        String key = uniq("c8bt");
        DeploymentItemDTO childItem = batchItem(bpmn("test-c8-child.bpmn")
            .replace("c8-child", childKey)
            .replace("C8MARKER", "\"v1\""));
        DeploymentItemDTO parentItem = batchItem(bpmn("test-c8-binding-deployment.bpmn")
            .replace("c8-binding-deployment", key)
            .replace("c8-callee", childKey));
        DeploymentDTO batch = deploymentService.deployBatch(List.of(childItem, parentItem), null, null);
        UUID parentPdId = batch.getProcesses().stream()
            .filter(p -> key.equals(p.getKey()))
            .findFirst().orElseThrow().getProcessDefinitionId();
        // новая версия вызываемого приземляется ПОСЛЕ деплоймента (отдельно, вне пачки).
        processDefinitionService.addProcessDefinition(
            bpmn("test-c8-child.bpmn")
                .replace("c8-child", childKey)
                .replace("C8MARKER", "\"v2\""));

        UUID piId = start(parentPdId, List.of());

        // исполняется v1 (маркер дочернего инстанса), не прилетевшая позже v2.
        // (Маркер пишется и в дочернем, и — WO-ENG-11 propagation — в родительском инстансе,
        // поэтому смотрим именно строку дочернего инстанса.)
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
    void bindingTypeLatest_stillResolvesLatest() throws Exception {
        // WO-C8-3b, критерий 2: bindingType="latest" — прежний путь, побеждает v2.
        String childKey = uniq("c8callee");
        processDefinitionService.addProcessDefinition(
            bpmn("test-c8-child.bpmn")
                .replace("c8-child", childKey)
                .replace("C8MARKER", "\"v1\""));
        String key = uniq("c8btl");
        String xml = bpmn("test-c8-binding-deployment.bpmn")
            .replace("c8-binding-deployment", key)
            .replace("c8-callee", childKey)
            .replace("bindingType=\"deployment\"", "bindingType=\"latest\"");
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);
        processDefinitionService.addProcessDefinition(
            bpmn("test-c8-child.bpmn")
                .replace("c8-child", childKey)
                .replace("C8MARKER", "\"v2\""));

        UUID piId = start(model.getId(), List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        List<ProcessVariableEntity> childMarkers = variableRepository.findAll().stream()
            .filter(v -> v.getName().equals("ranVersion"))
            .filter(v -> !v.getProcessInstanceId().equals(piId))
            .toList();
        assertThat(childMarkers).hasSize(1);
        assertThat(childMarkers.get(0).getTextValue()).isEqualTo("v2");
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    @Test
    @Transactional
    void bindingTypeAbsent_stillResolvesLatest() throws Exception {
        // WO-C8-3b, критерий 2: отсутствие атрибута — прежний путь (latest), побеждает v2.
        String childKey = uniq("c8callee");
        processDefinitionService.addProcessDefinition(
            bpmn("test-c8-child.bpmn")
                .replace("c8-child", childKey)
                .replace("C8MARKER", "\"v1\""));
        String key = uniq("c8bta");
        String xml = bpmn("test-c8-binding-deployment.bpmn")
            .replace("c8-binding-deployment", key)
            .replace("c8-callee", childKey)
            .replace(" bindingType=\"deployment\"", "");
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);
        processDefinitionService.addProcessDefinition(
            bpmn("test-c8-child.bpmn")
                .replace("c8-child", childKey)
                .replace("C8MARKER", "\"v2\""));

        UUID piId = start(model.getId(), List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        List<ProcessVariableEntity> childMarkers = variableRepository.findAll().stream()
            .filter(v -> v.getName().equals("ranVersion"))
            .filter(v -> !v.getProcessInstanceId().equals(piId))
            .toList();
        assertThat(childMarkers).hasSize(1);
        assertThat(childMarkers.get(0).getTextValue()).isEqualTo("v2");
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    @Test
    @Transactional
    void bindingTypeDeployment_childMissingFromDeployment_raisesIncident() throws Exception {
        // WO-C8-3b, критерий 3: вызываемого нет в деплойменте родителя — явный инцидент
        // с внятным текстом, НЕ тихий fallback в latest.
        String childKey = uniq("c8callee");
        processDefinitionService.addProcessDefinition(
            bpmn("test-c8-child.bpmn")
                .replace("c8-child", childKey)
                .replace("C8MARKER", "\"v1\""));
        String key = uniq("c8btm");
        DeploymentItemDTO parentItem = batchItem(bpmn("test-c8-binding-deployment.bpmn")
            .replace("c8-binding-deployment", key)
            .replace("c8-callee", childKey));
        DeploymentDTO batch = deploymentService.deployBatch(List.of(parentItem), null, null);
        UUID parentPdId = batch.getProcesses().stream()
            .filter(p -> key.equals(p.getKey()))
            .findFirst().orElseThrow().getProcessDefinitionId();

        UUID piId = start(parentPdId, List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();
        List<IncidentEntity> incidents = incidentsOfInstance(piId);
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getMessage()).contains(childKey).contains("bindingType=\"deployment\"");
    }

    @Test
    @Transactional
    void bindingTypeDeployment_singlyDeployedParent_raisesIncident() throws Exception {
        // WO-C8-3b + диспатч (следствие Option B): родитель выложен одиночкой (deployment_id
        // NULL) — пиннинг невозможен, явный инцидент с указанием перевыложить пачкой.
        String childKey = uniq("c8callee");
        processDefinitionService.addProcessDefinition(
            bpmn("test-c8-child.bpmn")
                .replace("c8-child", childKey)
                .replace("C8MARKER", "\"v1\""));
        String key = uniq("c8bts");
        String xml = bpmn("test-c8-binding-deployment.bpmn")
            .replace("c8-binding-deployment", key)
            .replace("c8-callee", childKey);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();
        List<IncidentEntity> incidents = incidentsOfInstance(piId);
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getMessage()).contains("POST /deployments");
    }

    private static DeploymentItemDTO batchItem(String bpmn) {
        DeploymentItemDTO item = new DeploymentItemDTO();
        item.setType(DeploymentResourceType.BPMN);
        item.setContent(bpmn);
        return item;
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
    void intermediateThrowInsideSubprocess_isParsedAndThrows() throws Exception {
        // WO-C8-14 GREEN (переименован из intermediateThrowInsideSubprocess_isDroppedByParser):
        // intermediateThrowEvent внутри сабпроцесса разбирается ТЕМ ЖЕ вызовом, что верхний
        // уровень (включая attachEventDefinition с escalation) — escalation реально бросается
        // и ловится boundary specific → endSpecific.
        String key = uniq("c8esp");
        String xml = bpmn("test-c8-escalation-priority.bpmn").replace("c8-escalation-priority", key);
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
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    @Test
    @Transactional
    void intermediateCatchInsideSubprocess_parksAndResumesOnMessage() throws Exception {
        // WO-C8-14, intermediateCatchEvent: message-catch внутри сабпроцесса паркуется и
        // продолжается по корреляции — элемент исполнился, а не только распарсился.
        String key = uniq("c8subc");
        String xml = bpmn("test-c8-sub-catch.bpmn").replace("c8-sub-catch", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();
        assertThat(activity(piId, "msgCatch").getStatus()).isEqualTo(ActivityStatus.CREATED);

        activityService.correlateMessage("ping", piId, List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        assertThat(activity(piId, "msgCatch").getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    @Test
    @Transactional
    void sendAndReceiveInsideSubprocess_passThroughAndCorrelate() throws Exception {
        // WO-C8-14, sendTask + receiveTask: send внутри сабпроцесса проходит насквозь,
        // receive паркуется и продолжается по корреляции (зеркало SendReceiveTaskIntegrationTests).
        String key = uniq("c8subsr");
        String xml = bpmn("test-c8-sub-send-receive.bpmn").replace("c8-sub-send-receive", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();
        assertThat(activity(piId, "sendTask1").getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        assertThat(activity(piId, "receiveTask1").getStatus()).isEqualTo(ActivityStatus.CREATED);

        activityService.correlateMessage("approve", piId, List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        List<ActivityEntity> activities = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(piId))
            .toList();
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("sendTask1") && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("receiveTask1") && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    @Test
    @Transactional
    void businessRuleInsideSubprocess_evaluatesDmnDecision() throws Exception {
        // WO-C8-14, businessRuleTask: DMN реально вычисляется внутри сабпроцесса, результат
        // в переменной (зеркало BusinessRuleTaskIntegrationTests).
        dmnService.deploy(bpmn("test-discount.dmn"));
        String key = uniq("c8subbr");
        String xml = bpmn("test-c8-sub-business-rule.bpmn").replace("c8-sub-business-rule", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of(var("category", ProcessVariableType.STRING, "gold")));

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        ProcessVariableEntity discount = variableRepository.findByNameAndProcessInstanceId("discount", piId).orElseThrow();
        assertThat(discount.getType()).isEqualTo(ProcessVariableType.LONG);
        assertThat(discount.getTextValue()).isEqualTo("20");
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    @Test
    @Transactional
    void inclusiveGatewayInsideSubprocess_evaluatesBranches() throws Exception {
        // WO-C8-14, inclusiveGateway: a=yes, b=yes — обе ветки активны, default нет; join ждёт
        // ровно взятые ветки (зеркало InclusiveGatewayIntegrationTests).
        String key = uniq("c8subinc");
        String xml = bpmn("test-c8-sub-inclusive.bpmn").replace("c8-sub-inclusive", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of(var("a", ProcessVariableType.STRING, "yes"), var("b", ProcessVariableType.STRING, "yes")));

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        List<ActivityEntity> activities = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(piId))
            .toList();
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("flowA") && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("flowB") && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(activities).noneMatch(a -> a.getBpmnElementId().equals("flowDefault"));
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("join") && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("endEvent") && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    @Test
    @Transactional
    void eventBasedGatewayInsideSubprocess_messageWinsRace() throws Exception {
        // WO-C8-14, eventBasedGateway: гонка внутри сабпроцесса — сообщение приходит первым,
        // его ветка идёт, таймерная отменяется (зеркало EventBasedGatewayIntegrationTests,
        // таймер не срабатывает — PT1H).
        String key = uniq("c8subebg");
        String xml = bpmn("test-c8-sub-ebg.bpmn").replace("c8-sub-ebg", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();

        activityService.correlateMessage("approve", piId, List.of());

        List<ActivityEntity> activities = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(piId))
            .toList();
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("msgCatch") && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("endApprove") && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("timerCatch") && a.getStatus() == ActivityStatus.CANCELLED);
        assertThat(activities).noneMatch(a -> a.getBpmnElementId().equals("endTimeout"));
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    // ==================== WO-C8-14b (A-2, срез 2): вложенные контейнеры и boundary ====================

    @Test
    @Transactional
    void callActivityInsideSubprocess_startsAndCompletesChild() throws Exception {
        // WO-C8-14b, callActivity: вызов дочернего процесса из сабпроцесса — ребёнок реально
        // стартует и завершается, родитель продолжается (зеркало CallActivity-тестов).
        processDefinitionService.addProcessDefinition(bpmn("test-eng11-child.bpmn"));
        String key = uniq("c8subcall");
        String xml = bpmn("test-c8-sub-call.bpmn").replace("c8-sub-call", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());

        List<ProcessInstanceEntity> children = processInstanceRepository
            .findAll(ProcessInstanceRepository.byParentProcessInstanceId(piId));
        assertThat(children).hasSize(1);
        assertThat(queryService.getProcessInstance(children.get(0).getId()).getCompletedAt()).isNotNull();
        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    @Test
    @Transactional
    void nestedSubprocess_executesThroughBothLevels() throws Exception {
        // WO-C8-14b, вложенный subProcess: рекурсия toSubProcessElement — поток проходит оба
        // уровня, внутренняя user task исполняется.
        String key = uniq("c8subnest");
        String xml = bpmn("test-c8-sub-nested.bpmn").replace("c8-sub-nested", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();
        assertThat(activity(piId, "innerReview").getStatus()).isEqualTo(ActivityStatus.CREATED);
        completeUserTask(piId, "innerReview");

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        assertThat(activity(piId, "innerReview").getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    @Test
    @Transactional
    void transactionInsideSubprocess_completesNormally() throws Exception {
        // WO-C8-14b, transaction: вложенная транзакция с service task завершается штатно,
        // родитель продолжается (cancel-путь — вне среза).
        String key = uniq("c8subtx");
        String xml = bpmn("test-c8-sub-transaction.bpmn").replace("c8-sub-transaction", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();
        UUID svcId = activity(piId, "svc").getId();
        assertThat(activity(piId, "svc").getStatus()).isEqualTo(ActivityStatus.CREATED);
        runtimeService.completeServiceTask(svcId, List.of());

        assertThat(activity(piId, "svc").getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    @Test
    void boundaryTimerOnTaskInsideSubprocess_interruptsIt() throws Exception {
        // WO-C8-14b, критерий 4 (самый ценный тест среза): timer-boundary на задаче внутри
        // сабпроцесса реально прерывает её — «таймаут на шаге внутри подпроцесса».
        // Committed-tx паттерн TimerCycle-прецедента (без @Transactional): файры видят только
        // закоммиченные строки; чистка — только свои timer jobs по окну времени.
        Instant startedAt = Instant.now();
        String key = uniq("c8subbt");
        String xml = bpmn("test-c8-sub-boundary-timer.bpmn").replace("c8-sub-boundary-timer", key);
        UUID[] piId = new UUID[1];
        inNewTx(() -> {
            try {
                ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);
                piId[0] = start(model.getId(), List.of());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        try {
            assertThat(queryService.getProcessInstance(piId[0]).getCompletedAt()).isNull();
            assertThat(activity(piId[0], "work").getStatus()).isEqualTo(ActivityStatus.CREATED);

            inNewTx(() -> fireDueTimersInWindow(startedAt));

            assertThat(activity(piId[0], "work").getStatus()).isEqualTo(ActivityStatus.CANCELLED);
            List<ActivityEntity> activities = activityRepository.findAll().stream()
                .filter(a -> a.getProcessInstanceId().equals(piId[0]))
                .toList();
            assertThat(activities).anyMatch(a -> "endTimeout".equals(a.getBpmnElementId()) && a.getStatus() == ActivityStatus.COMPLETED);
            assertThat(queryService.getProcessInstance(piId[0]).getCompletedAt()).isNotNull();
            assertThat(incidentsOfInstance(piId[0])).isEmpty();
        } finally {
            Instant since = startedAt;
            inNewTx(() -> deleteTimerJobsInWindow(since));
        }
    }

    @Test
    @Transactional
    void associationInsideSubprocess_linksCompensationHandler() throws Exception {
        // WO-C8-14b, association: compensate-boundary внутри сабпроцесса линкуется к хендлеру
        // через association из того же сабпроцесса — компенсация исполняется (зеркало
        // CompensationIntegrationTests, log = 0*10+1).
        String key = uniq("c8subcomp");
        String xml = bpmn("test-c8-sub-compensation.bpmn").replace("c8-sub-compensation", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of(var("log", ProcessVariableType.LONG, "0")));

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        ProcessVariableEntity result = variableRepository.findByNameAndProcessInstanceId("log", piId).orElseThrow();
        assertThat(result.getTextValue()).isEqualTo("1");
        List<ActivityEntity> activities = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(piId))
            .toList();
        assertThat(activities).anyMatch(a -> "handlerA".equals(a.getBpmnElementId()) && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    // ==================== WO-C8-16 (A-4): job-based end/throw events ====================

    @Test
    @Transactional
    void jobEndEvent_parksAndDispatchesJobUntilWorkerCompletes() throws Exception {
        // WO-C8-16: end event с zeebe:taskDefinition паркуется как job (outbox), а не проходит
        // насквозь; завершение воркером заканчивает ветку. Headers/priority едут тем же кодом.
        String key = uniq("c8je");
        String xml = bpmn("test-c8-job-end.bpmn").replace("c8-job-end", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());

        // parked, not passed through: instance running, end activity CREATED, job dispatched
        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();
        UUID activityId = activity(piId, "end").getId();
        assertThat(activity(piId, "end").getStatus()).isEqualTo(ActivityStatus.CREATED);
        assertThat(serviceTaskJobs(piId)).containsExactly("job-c8");

        // criterion 3: taskHeaders + jobPriorityDefinition rode along to the worker
        String payload = outboxPayload(piId);
        assertThat(payload).contains("\"taskHeaders\"");
        assertThat(payload).contains("acme");
        assertThat(payload).contains("\"priority\":75");

        runtimeService.completeServiceTask(activityId, List.of());

        assertThat(activity(piId, "end").getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    @Test
    @Transactional
    void jobThrowEvent_parksAndContinuesAfterWorkerCompletes() throws Exception {
        // WO-C8-16: message throw с zeebe:taskDefinition паркуется — внутренняя корреляция НЕ
        // происходит (отправка за воркером); продолжение потока — после завершения job'а.
        String key = uniq("c8jt");
        String xml = bpmn("test-c8-job-throw.bpmn").replace("c8-job-throw", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();
        UUID activityId = activity(piId, "msgThrow").getId();
        assertThat(activity(piId, "msgThrow").getStatus()).isEqualTo(ActivityStatus.CREATED);
        assertThat(serviceTaskJobs(piId)).containsExactly("job-c8");

        runtimeService.completeServiceTask(activityId, List.of());

        assertThat(activity(piId, "msgThrow").getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    @Test
    @Transactional
    void escalationThrowWithJobDefinition_stillThrowsSynchronously() throws Exception {
        // WO-C8-16 HOLD (находка CTO): escalation-throw с taskDefinition НЕ паркуется — у броска
        // нет воркерного эквивалента (хендлер вызывает throwEscalation, завершение job'а ушло бы
        // в общий proceedToOutgoing мимо хендлера). Бросок происходит как раньше: boundary
        // specific ловит, job в outbox НЕ диспетчеризуется.
        String key = uniq("c8etj");
        String xml = bpmn("test-c8-escalation-throw-with-job.bpmn").replace("c8-escalation-throw-job", key);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);

        UUID piId = start(model.getId(), List.of());

        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNotNull();
        Set<String> completedEnds = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(piId))
            .filter(a -> a.getStatus() == ActivityStatus.COMPLETED)
            .map(ActivityEntity::getBpmnElementId)
            .collect(Collectors.toSet());
        assertThat(completedEnds).contains("endSpecific");
        assertThat(serviceTaskJobs(piId)).isEmpty();
        assertThat(incidentsOfInstance(piId)).isEmpty();
    }

    /** WO-C8-16: payload of this instance's first outbox SERVICE_TASK entry. */
    private String outboxPayload(UUID processInstanceId) throws Exception {
        tools.jackson.databind.ObjectMapper om = new tools.jackson.databind.ObjectMapper();
        List<OutboxEntry> entries = outboxRepository.findAll().stream()
            .filter(e -> e.getKind() == OutboxKind.SERVICE_TASK)
            .filter(e -> {
                try {
                    return processInstanceId.toString().equals(om.readTree(e.getPayload()).get("processInstanceId").asText());
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            })
            .sorted(java.util.Comparator.comparing(OutboxEntry::getCreatedAt).thenComparing(OutboxEntry::getId))
            .collect(Collectors.toList());
        assertThat(entries).as("outbox entries of this instance").isNotEmpty();
        return entries.get(0).getPayload();
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
