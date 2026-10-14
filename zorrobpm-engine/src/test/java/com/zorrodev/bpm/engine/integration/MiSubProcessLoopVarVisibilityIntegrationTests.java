package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.handler.ElementSupport;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.entity.OutboxKind;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-42: цикл-переменная ({@code activity}) и lane-переменные MI-подпроцесса
 * видны СВОЕМУ инстансу, в том числе на повторном входе (отчёт консьюмера
 * 2026-10-14, дополнение к {@code zorrobpm-mi-subprocess-defect.md}).
 *
 * <p>Фикстура — реальный v2 консьюмера
 * ({@code test-c842-approval-route-v2.bpmn} — побайтовая копия
 * {@code docs/internal}).
 * Внешних воркеров нет: service task'и завершаем через
 * {@code runtimeService.completeServiceTask} (тот же путь, что worker-callback),
 * ответы воркеров эмулируем переменными завершения — включая вариант H1, где
 * воркер возвращает эхом ВСЕ переменные полученной джобы (в т.ч. {@code activity}).
 *
 * <p>Полезная нагрузка джобы читается из outbox (то же тело, что ушло бы воркеру):
 * {@code srvWorkTaskDecide} каждой ветки обязан нести {@code activityUuid} СВОЕГО
 * activity и свой {@code taskUuid} — иначе PATCH уходит по чужому (уже закрытому)
 * activity и ветка остаётся открытой (дефект п.1 консьюмера).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class MiSubProcessLoopVarVisibilityIntegrationTests {

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
    private IncidentRepository incidentRepository;
    @Autowired
    private OutboxRepository outboxRepository;
    @Autowired
    private com.zorrodev.bpm.engine.repository.UserTaskRepository userTaskRepository;
    @Autowired
    private ElementSupport elementSupport;

    private UUID deploy() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-c842-approval-route-v2.bpmn"));
        return processDefinitionService.addProcessDefinition(bpmn).getId();
    }

    private UUID deployF1() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-c842-f1-output-element-stale-root.bpmn"));
        return processDefinitionService.addProcessDefinition(bpmn).getId();
    }

    /** Значение JSON-переменной из видимого контекста activity (та же цепочка, что у движка). */
    private Map<String, Object> visibleJson(UUID pi, UUID activityId, String name) throws Exception {
        for (ProcessVariable v : elementSupport.visibleVariables(pi, activityId)) {
            if (name.equals(v.getName()) && v.getValue() != null) {
                return new ObjectMapper().readValue(v.getValue(),
                    new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
            }
        }
        throw new AssertionError("variable " + name + " not visible for activity " + activityId);
    }

    private List<ActivityEntity> activities(UUID pi, String bpmnElementId, ActivityStatus status) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getBpmnElementId().equals(bpmnElementId) && a.getStatus() == status)
            .toList();
    }

    private long incidents(UUID pi) {
        var activityIds = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .map(ActivityEntity::getId)
            .collect(Collectors.toSet());
        return incidentRepository.findAll().stream()
            .filter(i -> activityIds.contains(i.getActivityId()))
            .count();
    }

    private String assigneeOf(UUID activityId) {
        return userTaskRepository.findById(activityId).map(u -> u.getAssignee()).orElse(null);
    }

    private static ProcessVariable pv(String name, ProcessVariableType type, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(type);
        v.setValue(value);
        return v;
    }

    /** Payload-переменные outbox-джобы (то же тело, что ушло бы воркеру). */
    private Map<String, String> jobVariables(UUID pi, String job, UUID serviceTaskId) throws Exception {
        ObjectMapper om = new ObjectMapper();
        List<OutboxEntry> entries = outboxRepository.findAll().stream()
            .filter(e -> e.getKind() == OutboxKind.SERVICE_TASK)
            .filter(e -> {
                try {
                    var node = om.readTree(e.getPayload());
                    return pi.toString().equals(node.get("processInstanceId").asText())
                        && job.equals(node.get("job").asText())
                        && serviceTaskId.toString().equals(node.get("serviceTaskId").asText());
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }).toList();
        assertThat(entries).as("outbox for " + job + " " + serviceTaskId).hasSize(1);
        Map<String, String> result = new LinkedHashMap<>();
        var varsNode = om.readTree(entries.get(0).getPayload()).get("variables");
        var names = varsNode.propertyNames().iterator();
        while (names.hasNext()) {
            String name = names.next();
            result.put(name, varsNode.get(name).get("value").asText());
        }
        return result;
    }

    /** activity-объект ветки из payload create-джобы (uuid + employeeId своего элемента). */
    private Map<String, Object> branchActivityOf(Map<String, String> createPayload) throws Exception {
        assertThat(createPayload).as("create payload carries branch activity").containsKey("activity");
        return new ObjectMapper().readValue(createPayload.get("activity"),
            new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    /** Root-строка переменной (scope IS NULL) — пусто, если имя живёт только в скоупах. */
    private List<ProcessVariableEntity> rootRows(UUID pi, String name) {
        return variableRepository.findByProcessInstanceIdAndScopeIdIsNull(pi).stream()
            .filter(v -> name.equals(v.getName()))
            .toList();
    }

    private UUID startRouted(UUID pdId, String routeTag) {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        dto.setVariables(List.of(
            pv("createdEmployeeId", ProcessVariableType.LONG, "100"),
            pv("routeUuid", ProcessVariableType.STRING, routeTag),
            pv("processTypeId", ProcessVariableType.LONG, "4"),
            pv("sourceUuid", ProcessVariableType.STRING, "src-" + routeTag),
            pv("sourceVersion", ProcessVariableType.LONG, "1"),
            pv("stages", ProcessVariableType.JSON, "[]"),
            pv("resourceUuid", ProcessVariableType.STRING, "res-" + routeTag)));
        return runtimeService.startProcessInstance(dto).getId();
    }

    /** Setup-fork + srvRouteStart + один опрос с заданной коллекцией. */
    private void driveSetupAndPoll(UUID pi, String activitiesJson, String stageModeId) {
        for (String setup : List.of("srvRouteCreate", "srvRouteSourceMapLink",
            "srvStageCreateWithActivities", "srvResourceCreate")) {
            List<ActivityEntity> jobs = activities(pi, setup, ActivityStatus.CREATED);
            assertThat(jobs).as("setup job " + setup).hasSize(1);
            if ("srvRouteCreate".equals(setup)) {
                runtimeService.completeServiceTask(jobs.get(0).getId(),
                    List.of(pv("routeUuid", ProcessVariableType.STRING, "route-" + pi.toString().substring(0, 4))));
            } else {
                runtimeService.completeServiceTask(jobs.get(0).getId(), List.of());
            }
        }
        List<ActivityEntity> starters = activities(pi, "srvRouteStart", ActivityStatus.CREATED);
        assertThat(starters).hasSize(1);
        runtimeService.completeServiceTask(starters.get(0).getId(), List.of());
        List<ActivityEntity> polls = activities(pi, "srvRouteOpenActivities", ActivityStatus.CREATED);
        assertThat(polls).hasSize(1);
        runtimeService.completeServiceTask(polls.get(0).getId(), List.of(
            pv("routeStageUuid", ProcessVariableType.STRING, "stage-1"),
            pv("stageModeId", ProcessVariableType.LONG, stageModeId),
            pv("activities", ProcessVariableType.JSON, activitiesJson),
            pv("hasOpen", ProcessVariableType.BOOLEAN, "true"),
            pv("hasRejected", ProcessVariableType.BOOLEAN, "false")));
    }

    /**
     * Этап 1, сдача первой ветки первой: decide каждой ветки несёт activityUuid
     * СВОЕГО activity и свой taskUuid; после join в root нет lane-/loop-переменных.
     */
    @Transactional
    @Test
    void decidePayload_branchOneFirst_carriesOwnActivityUuid() throws Exception {
        UUID pdId = deploy();
        UUID pi = startRouted(pdId, "c842-t1");
        driveSetupAndPoll(pi, "[{\"employeeId\":21346,\"uuid\":\"a1\"},{\"employeeId\":29250,\"uuid\":\"a2\"}]", "2");

        List<ActivityEntity> creates = activities(pi, "srvWorkTaskCreate", ActivityStatus.CREATED);
        assertThat(creates).as("2 parallel srvWorkTaskCreate").hasSize(2);

        // Ветка ↔ activity — из payload каждой create-джобы.
        Map<String, String> taskOfBranch = new LinkedHashMap<>();
        Map<String, String> activityOfBranch = new LinkedHashMap<>();
        int n = 0;
        for (ActivityEntity job : creates) {
            Map<String, String> vars = jobVariables(pi, "srvWorkTaskCreate", job.getId());
            Map<String, Object> act = branchActivityOf(vars);
            String key = act.get("employeeId") + "/" + act.get("uuid");
            taskOfBranch.put(key, "task-t1-" + (++n));
            activityOfBranch.put(key, String.valueOf(act.get("uuid")));
            runtimeService.completeServiceTask(job.getId(),
                List.of(pv("taskUuid", ProcessVariableType.STRING, taskOfBranch.get(key))));
        }
        assertThat(activityOfBranch.values()).containsExactlyInAnyOrder("a1", "a2");

        List<ActivityEntity> userTasks = activities(pi, "utWorkTaskExecute", ActivityStatus.CREATED);
        assertThat(userTasks).as("2 parallel user tasks").hasSize(2);
        assertThat(userTasks.stream().map(a -> assigneeOf(a.getId())).toList())
            .containsExactlyInAnyOrder("21346", "29250");

        // Сдача первой ветки (21346/a1) → decide только её ветки.
        UUID ut1 = userTasks.stream().filter(a -> "21346".equals(assigneeOf(a.getId())))
            .map(ActivityEntity::getId).findFirst().orElseThrow();
        runtimeService.completeUserTask(ut1, List.of(
            pv("decision", ProcessVariableType.STRING, "APPROVED"),
            pv("comment", ProcessVariableType.STRING, "ok-a1")));
        List<ActivityEntity> decides = activities(pi, "srvWorkTaskDecide", ActivityStatus.CREATED);
        assertThat(decides).as("decide of the completed branch starts immediately").hasSize(1);
        Map<String, String> d1 = jobVariables(pi, "srvWorkTaskDecide", decides.get(0).getId());
        assertThat(d1.get("activityUuid")).as("decide-1 carries activityUuid of its own branch (a1)").isEqualTo("a1");
        assertThat(d1.get("taskUuid")).as("decide-1 carries taskUuid of its own branch").isEqualTo(taskOfBranch.get("21346/a1"));
        assertThat(d1.get("decision")).as("decide-1 carries its branch decision").isEqualTo("APPROVED");
        runtimeService.completeServiceTask(decides.get(0).getId(), List.of());

        // Сдача второй ветки (29250/a2) → join.
        UUID ut2 = activities(pi, "utWorkTaskExecute", ActivityStatus.CREATED).get(0).getId();
        runtimeService.completeUserTask(ut2, List.of(
            pv("decision", ProcessVariableType.STRING, "APPROVED"),
            pv("comment", ProcessVariableType.STRING, "ok-a2")));
        List<ActivityEntity> decides2 = activities(pi, "srvWorkTaskDecide", ActivityStatus.CREATED);
        assertThat(decides2).as("decide of the second branch").hasSize(1);
        Map<String, String> d2 = jobVariables(pi, "srvWorkTaskDecide", decides2.get(0).getId());
        assertThat(d2.get("activityUuid")).as("decide-2 carries activityUuid of its own branch (a2)").isEqualTo("a2");
        assertThat(d2.get("taskUuid")).as("decide-2 carries taskUuid of its own branch").isEqualTo(taskOfBranch.get("29250/a2"));
        runtimeService.completeServiceTask(decides2.get(0).getId(), List.of());

        ObjectMapper om = new ObjectMapper();
        ProcessVariableEntity decisions = variableRepository
            .findByNameAndProcessInstanceId("decisions", pi).orElseThrow();
        List<Map<String, Object>> rows = om.readValue(decisions.getTextValue(),
            new tools.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
        assertThat(rows).as("decisions aggregated in instance order").hasSize(2);
        assertThat(rows.stream().map(r -> String.valueOf(r.get("decision"))).toList())
            .containsExactly("APPROVED", "APPROVED");
        assertThat(rows.stream().map(r -> String.valueOf(r.get("comment"))).toList())
            .containsExactly("ok-a1", "ok-a2");

        // В root после join — только decisions/activities, lane-/loop-переменных нет.
        for (String lane : List.of("activity", "loopCounter", "decision", "comment", "taskUuid")) {
            assertThat(rootRows(pi, lane)).as("no root row for lane/loop variable " + lane).isEmpty();
        }
        assertThat(activities(pi, "srvRouteOpenActivities", ActivityStatus.CREATED))
            .as("all APPROVED returns to open-activities poll").hasSize(1);
        assertThat(incidents(pi)).as("no incidents").isZero();
    }

    /**
     * Этап 1, сдача ВТОРОЙ ветки первой + воркер возвращает эхом все переменные
     * джобы (H1): decide второй ветки обязан нести a2, а не a1; root чист.
     */
    @Transactional
    @Test
    void decidePayload_branchTwoFirstWithWorkerEcho_carriesOwnActivityUuid() throws Exception {
        UUID pdId = deploy();
        UUID pi = startRouted(pdId, "c842-t2");
        driveSetupAndPoll(pi, "[{\"employeeId\":21346,\"uuid\":\"a1\"},{\"employeeId\":29250,\"uuid\":\"a2\"}]", "2");

        List<ActivityEntity> creates = activities(pi, "srvWorkTaskCreate", ActivityStatus.CREATED);
        assertThat(creates).as("2 parallel srvWorkTaskCreate").hasSize(2);

        // H1: воркер возвращает эхом все переменные полученной джобы, в т.ч. activity.
        Map<String, String> taskOfBranch = new LinkedHashMap<>();
        Map<String, String> activityJsonOfBranch = new LinkedHashMap<>();
        List<String> echoKeys = new ArrayList<>();
        int n = 0;
        for (ActivityEntity job : creates) {
            Map<String, String> vars = jobVariables(pi, "srvWorkTaskCreate", job.getId());
            Map<String, Object> act = branchActivityOf(vars);
            String key = act.get("employeeId") + "/" + act.get("uuid");
            taskOfBranch.put(key, "task-t2-" + (++n));
            activityJsonOfBranch.put(key, vars.get("activity"));
            List<ProcessVariable> echo = new ArrayList<>();
            echo.add(pv("taskUuid", ProcessVariableType.STRING, taskOfBranch.get(key)));
            for (Map.Entry<String, String> e : vars.entrySet()) {
                if ("taskUuid".equals(e.getKey())) {
                    continue;
                }
                echo.add(pv(e.getKey(), ProcessVariableType.STRING, e.getValue()));
                echoKeys.add(e.getKey());
            }
            runtimeService.completeServiceTask(job.getId(), echo);
        }
        assertThat(echoKeys).as("echo really covers the job payload (H1)").contains("activity");

        List<ActivityEntity> userTasks = activities(pi, "utWorkTaskExecute", ActivityStatus.CREATED);
        assertThat(userTasks).as("2 parallel user tasks").hasSize(2);

        // Сдача ВТОРОЙ ветки первой (точный порядок консьюмера) — с эхом taskUuid
        // и activity (полный H1 и на user-task пути: клиент шлёт всё, что видел).
        UUID ut2 = userTasks.stream().filter(a -> "29250".equals(assigneeOf(a.getId())))
            .map(ActivityEntity::getId).findFirst().orElseThrow();
        UUID ut1 = userTasks.stream().filter(a -> "21346".equals(assigneeOf(a.getId())))
            .map(ActivityEntity::getId).findFirst().orElseThrow();
        Map<String, String> ut2vars = new LinkedHashMap<>();
        ut2vars.put("decision", "APPROVED");
        ut2vars.put("comment", "ok-a2");
        runtimeService.completeUserTask(ut2, List.of(
            pv("decision", ProcessVariableType.STRING, "APPROVED"),
            pv("comment", ProcessVariableType.STRING, "ok-a2"),
            pv("taskUuid", ProcessVariableType.STRING, taskOfBranch.get("29250/a2")),
            pv("activity", ProcessVariableType.JSON, activityJsonOfBranch.get("29250/a2"))));
        List<ActivityEntity> decides = activities(pi, "srvWorkTaskDecide", ActivityStatus.CREATED);
        assertThat(decides).as("decide of the completed (second) branch starts immediately").hasSize(1);
        Map<String, String> d2 = jobVariables(pi, "srvWorkTaskDecide", decides.get(0).getId());
        assertThat(d2.get("activityUuid")).as("decide-2 carries activityUuid of its own branch (a2), not a1").isEqualTo("a2");
        assertThat(d2.get("taskUuid")).as("decide-2 carries taskUuid of its own branch").isEqualTo(taskOfBranch.get("29250/a2"));
        runtimeService.completeServiceTask(decides.get(0).getId(), List.of(
            pv("decision", ProcessVariableType.STRING, "APPROVED")));

        runtimeService.completeUserTask(ut1, List.of(
            pv("decision", ProcessVariableType.STRING, "APPROVED"),
            pv("comment", ProcessVariableType.STRING, "ok-a1"),
            pv("taskUuid", ProcessVariableType.STRING, taskOfBranch.get("21346/a1"))));
        List<ActivityEntity> decides2 = activities(pi, "srvWorkTaskDecide", ActivityStatus.CREATED);
        assertThat(decides2).as("decide of the first branch").hasSize(1);
        Map<String, String> d1 = jobVariables(pi, "srvWorkTaskDecide", decides2.get(0).getId());
        assertThat(d1.get("activityUuid")).as("decide-1 carries activityUuid of its own branch (a1)").isEqualTo("a1");
        assertThat(d1.get("taskUuid")).as("decide-1 carries taskUuid of its own branch").isEqualTo(taskOfBranch.get("21346/a1"));
        runtimeService.completeServiceTask(decides2.get(0).getId(), List.of(
            pv("decision", ProcessVariableType.STRING, "APPROVED")));

        ObjectMapper om = new ObjectMapper();
        ProcessVariableEntity decisions = variableRepository
            .findByNameAndProcessInstanceId("decisions", pi).orElseThrow();
        List<Map<String, Object>> rows = om.readValue(decisions.getTextValue(),
            new tools.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
        assertThat(rows).as("decisions aggregated in instance order").hasSize(2);
        // Агрегация позиционная (loopCounter-слот, паритет Zeebe S1-3): порядок
        // инстансов, а не порядок сдачи — a2 сдана первой, но слот 0 за a1.
        assertThat(rows.stream().map(r -> String.valueOf(r.get("comment"))).toList())
            .containsExactly("ok-a1", "ok-a2");

        for (String lane : List.of("activity", "loopCounter", "decision", "comment", "taskUuid")) {
            assertThat(rootRows(pi, lane)).as("echo (H1) leaves no root row for " + lane).isEmpty();
        }
        assertThat(incidents(pi)).as("no incidents").isZero();
    }

    /**
     * Повторный вход (этап 2, новые uuid b1/b2): assignee и decide каждой ветки —
     * по ЭЛЕМЕНТУ ТЕКУЩЕЙ коллекции; ровно 4 user task за прогон; endRouteCompleted.
     */
    @Transactional
    @Test
    void reentry_usesCurrentCollection_notStaleRoot() throws Exception {
        UUID pdId = deploy();
        UUID pi = startRouted(pdId, "c842-t3");
        driveSetupAndPoll(pi, "[{\"employeeId\":21346,\"uuid\":\"a1\"},{\"employeeId\":29250,\"uuid\":\"a2\"}]", "2");

        // Этап 1 — быстро, без эха.
        List<ActivityEntity> creates1 = activities(pi, "srvWorkTaskCreate", ActivityStatus.CREATED);
        assertThat(creates1).as("stage-1: 2 parallel creates").hasSize(2);
        int n = 0;
        for (ActivityEntity job : creates1) {
            runtimeService.completeServiceTask(job.getId(),
                List.of(pv("taskUuid", ProcessVariableType.STRING, "task-s1-" + (++n))));
        }
        List<ActivityEntity> uts1 = activities(pi, "utWorkTaskExecute", ActivityStatus.CREATED);
        assertThat(uts1).as("stage-1: 2 user tasks").hasSize(2);
        for (ActivityEntity ut : uts1) {
            runtimeService.completeUserTask(ut.getId(), List.of(
                pv("decision", ProcessVariableType.STRING, "APPROVED"),
                pv("comment", ProcessVariableType.STRING, "s1")));
            List<ActivityEntity> dec = activities(pi, "srvWorkTaskDecide", ActivityStatus.CREATED);
            assertThat(dec).hasSize(1);
            runtimeService.completeServiceTask(dec.get(0).getId(), List.of());
        }

        // Этап 2: опрос отдаёт НОВУЮ коллекцию (те же сотрудники, новые uuid).
        List<ActivityEntity> polls2 = activities(pi, "srvRouteOpenActivities", ActivityStatus.CREATED);
        assertThat(polls2).as("returned to poll after stage-1 join").hasSize(1);
        runtimeService.completeServiceTask(polls2.get(0).getId(), List.of(
            pv("routeStageUuid", ProcessVariableType.STRING, "stage-2"),
            pv("stageModeId", ProcessVariableType.LONG, "1"),
            pv("activities", ProcessVariableType.JSON,
                "[{\"employeeId\":21346,\"uuid\":\"b1\"},{\"employeeId\":29250,\"uuid\":\"b2\"}]"),
            pv("hasOpen", ProcessVariableType.BOOLEAN, "true"),
            pv("hasRejected", ProcessVariableType.BOOLEAN, "false")));

        // Повторный вход: create-джобы несут assigneeEmployeeId ТЕКУЩЕЙ коллекции.
        List<ActivityEntity> creates2 = activities(pi, "srvWorkTaskCreate", ActivityStatus.CREATED);
        assertThat(creates2).as("stage-2: re-entry spawns creates").hasSize(2);
        Map<String, String> taskOfB = new LinkedHashMap<>();
        List<String> stage2assignees = new ArrayList<>();
        int m = 0;
        for (ActivityEntity job : creates2) {
            Map<String, String> vars = jobVariables(pi, "srvWorkTaskCreate", job.getId());
            Map<String, Object> act = branchActivityOf(vars);
            stage2assignees.add(vars.get("assigneeEmployeeId"));
            assertThat(String.valueOf(act.get("uuid"))).as("re-entry create sees current collection uuid")
                .isIn("b1", "b2");
            String key = act.get("employeeId") + "/" + act.get("uuid");
            taskOfB.put(key, "task-s2-" + (++m));
            runtimeService.completeServiceTask(job.getId(),
                List.of(pv("taskUuid", ProcessVariableType.STRING, taskOfB.get(key))));
        }
        assertThat(stage2assignees).as("re-entry creates carry current-collection assignees")
            .containsExactlyInAnyOrder("21346", "29250");

        List<ActivityEntity> uts2 = activities(pi, "utWorkTaskExecute", ActivityStatus.CREATED);
        assertThat(uts2).as("stage-2: 2 user tasks").hasSize(2);
        assertThat(uts2.stream().map(a -> assigneeOf(a.getId())).toList())
            .containsExactlyInAnyOrder("21346", "29250");

        // Decide каждого шага несёт activityUuid b1/b2 (не stale a1/a2).
        for (ActivityEntity ut : uts2) {
            String who = assigneeOf(ut.getId());
            runtimeService.completeUserTask(ut.getId(), List.of(
                pv("decision", ProcessVariableType.STRING, "APPROVED"),
                pv("comment", ProcessVariableType.STRING, "s2-" + who)));
            List<ActivityEntity> dec = activities(pi, "srvWorkTaskDecide", ActivityStatus.CREATED);
            assertThat(dec).hasSize(1);
            Map<String, String> d = jobVariables(pi, "srvWorkTaskDecide", dec.get(0).getId());
            String expectUuid = "21346".equals(who) ? "b1" : "b2";
            assertThat(d.get("activityUuid")).as("stage-2 decide of " + who + " carries " + expectUuid).isEqualTo(expectUuid);
            assertThat(d.get("taskUuid")).as("stage-2 decide of " + who + " carries its taskUuid")
                .isEqualTo(taskOfB.get(who + "/" + expectUuid));
            runtimeService.completeServiceTask(dec.get(0).getId(), List.of());
        }

        // Опрос-3: открытых нет → endRouteCompleted. Ровно 4 user task, 0 инцидентов.
        List<ActivityEntity> polls3 = activities(pi, "srvRouteOpenActivities", ActivityStatus.CREATED);
        assertThat(polls3).as("returned to poll after stage-2 join").hasSize(1);
        runtimeService.completeServiceTask(polls3.get(0).getId(), List.of(
            pv("routeStageUuid", ProcessVariableType.STRING, "stage-2"),
            pv("stageModeId", ProcessVariableType.LONG, "1"),
            pv("activities", ProcessVariableType.JSON, "[]"),
            pv("hasOpen", ProcessVariableType.BOOLEAN, "false"),
            pv("hasRejected", ProcessVariableType.BOOLEAN, "false")));
        assertThat(activities(pi, "endRouteCompleted", ActivityStatus.COMPLETED))
            .as("route completed end-to-end").hasSize(1);
        assertThat(activities(pi, "utWorkTaskExecute", ActivityStatus.COMPLETED))
            .as("exactly 4 user tasks in the whole run (2+2, no re-entry loop)").hasSize(4);
        assertThat(activities(pi, "srvWorkTaskCreate", ActivityStatus.COMPLETED)).hasSize(4);
        for (String lane : List.of("activity", "loopCounter", "decision", "comment", "taskUuid")) {
            assertThat(rootRows(pi, lane)).as("no stale root row for " + lane + " after re-entry").isEmpty();
        }
        assertThat(incidents(pi)).as("no incidents").isZero();
    }

    /**
     * Stale root (наследие C8-40 / чужие root-писатели): в root уже лежат
     * {@code activity} и {@code loopCounter} с чужими значениями ДО входа в MI.
     * Инстанс-байдны обязаны побеждать (nearest-wins): create-джобы несут своих
     * assignee, decide — свои activityUuid, root остаётся нетронутым старым.
     */
    @Transactional
    @Test
    void staleRootLoopVars_doNotShadowInstanceBinds() throws Exception {
        UUID pdId = deploy();
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        dto.setVariables(List.of(
            pv("createdEmployeeId", ProcessVariableType.LONG, "100"),
            pv("routeUuid", ProcessVariableType.STRING, "c842-t4"),
            pv("processTypeId", ProcessVariableType.LONG, "4"),
            pv("sourceUuid", ProcessVariableType.STRING, "src-c842-t4"),
            pv("sourceVersion", ProcessVariableType.LONG, "1"),
            pv("stages", ProcessVariableType.JSON, "[]"),
            pv("resourceUuid", ProcessVariableType.STRING, "res-c842-t4"),
            pv("activity", ProcessVariableType.JSON, "{\"employeeId\":99999,\"uuid\":\"stale\"}"),
            pv("loopCounter", ProcessVariableType.LONG, "999")));
        UUID pi = runtimeService.startProcessInstance(dto).getId();
        driveSetupAndPoll(pi, "[{\"employeeId\":21346,\"uuid\":\"a1\"},{\"employeeId\":29250,\"uuid\":\"a2\"}]", "2");

        List<ActivityEntity> creates = activities(pi, "srvWorkTaskCreate", ActivityStatus.CREATED);
        assertThat(creates).as("2 parallel srvWorkTaskCreate").hasSize(2);
        Map<String, String> taskOfBranch = new LinkedHashMap<>();
        int n = 0;
        for (ActivityEntity job : creates) {
            Map<String, String> vars = jobVariables(pi, "srvWorkTaskCreate", job.getId());
            assertThat(vars.get("assigneeEmployeeId")).as("stale root must not leak into create payload")
                .isIn("21346", "29250");
            Map<String, Object> act = branchActivityOf(vars);
            assertThat(String.valueOf(act.get("uuid"))).as("create payload activity is the branch element")
                .isIn("a1", "a2");
            String key = act.get("employeeId") + "/" + act.get("uuid");
            taskOfBranch.put(key, "task-t4-" + (++n));
            runtimeService.completeServiceTask(job.getId(),
                List.of(pv("taskUuid", ProcessVariableType.STRING, taskOfBranch.get(key))));
        }

        List<ActivityEntity> userTasks = activities(pi, "utWorkTaskExecute", ActivityStatus.CREATED);
        assertThat(userTasks).as("2 parallel user tasks").hasSize(2);
        assertThat(userTasks.stream().map(a -> assigneeOf(a.getId())).toList())
            .as("stale root must not leak into user-task assignees")
            .containsExactlyInAnyOrder("21346", "29250");

        for (ActivityEntity ut : userTasks) {
            String who = assigneeOf(ut.getId());
            runtimeService.completeUserTask(ut.getId(), List.of(
                pv("decision", ProcessVariableType.STRING, "APPROVED"),
                pv("comment", ProcessVariableType.STRING, "ok")));
            List<ActivityEntity> dec = activities(pi, "srvWorkTaskDecide", ActivityStatus.CREATED);
            assertThat(dec).hasSize(1);
            Map<String, String> d = jobVariables(pi, "srvWorkTaskDecide", dec.get(0).getId());
            String expectUuid = "21346".equals(who) ? "a1" : "a2";
            assertThat(d.get("activityUuid")).as("decide of " + who + " carries " + expectUuid + ", not stale")
                .isEqualTo(expectUuid);
            runtimeService.completeServiceTask(dec.get(0).getId(), List.of());
        }

        // Stale root не затиран инстансами (чужое — не трогаем), но и не использован.
        assertThat(rootRows(pi, "activity").get(0).getTextValue()).as("stale root activity untouched")
            .contains("stale");
        assertThat(incidents(pi)).as("no incidents").isZero();
    }

    /**
     * Расходящееся эхо воркера: create-джоба ветки a1 завершается с activity,
     * ПРОТИВОРЕЧАЩИМ байнду инстанса (протухший кэш воркера). Байнд инстанса
     * (engine-owned) обязан уцелеть: decide-1 несёт a1, а не WRONG.
     */
    @Transactional
    @Test
    void divergentWorkerEcho_doesNotClobberInstanceBind() throws Exception {
        UUID pdId = deploy();
        UUID pi = startRouted(pdId, "c842-t5");
        driveSetupAndPoll(pi, "[{\"employeeId\":21346,\"uuid\":\"a1\"},{\"employeeId\":29250,\"uuid\":\"a2\"}]", "2");

        List<ActivityEntity> creates = activities(pi, "srvWorkTaskCreate", ActivityStatus.CREATED);
        assertThat(creates).as("2 parallel srvWorkTaskCreate").hasSize(2);
        Map<String, String> taskOfBranch = new LinkedHashMap<>();
        int n = 0;
        for (ActivityEntity job : creates) {
            Map<String, String> vars = jobVariables(pi, "srvWorkTaskCreate", job.getId());
            Map<String, Object> act = branchActivityOf(vars);
            String key = act.get("employeeId") + "/" + act.get("uuid");
            taskOfBranch.put(key, "task-t5-" + (++n));
            List<ProcessVariable> completion = new ArrayList<>();
            completion.add(pv("taskUuid", ProcessVariableType.STRING, taskOfBranch.get(key)));
            if ("21346/a1".equals(key)) {
                // Ветка a1: воркер вернул чужой activity (протухший кэш).
                completion.add(pv("activity", ProcessVariableType.JSON,
                    "{\"employeeId\":29250,\"uuid\":\"WRONG\"}"));
                completion.add(pv("loopCounter", ProcessVariableType.LONG, "777"));
            }
            runtimeService.completeServiceTask(job.getId(), completion);
        }

        List<ActivityEntity> userTasks = activities(pi, "utWorkTaskExecute", ActivityStatus.CREATED);
        assertThat(userTasks).as("2 parallel user tasks").hasSize(2);
        UUID ut1 = userTasks.stream().filter(a -> "21346".equals(assigneeOf(a.getId())))
            .map(ActivityEntity::getId).findFirst().orElseThrow();
        // Байнд инстанса a1 цел, несмотря на расходящееся эхо, — проверено напрямую.
        List<ActivityEntity> containers = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> "subWorkTaskStage".equals(a.getBpmnElementId())
                && (a.getStatus() == ActivityStatus.CREATED || a.getStatus() == ActivityStatus.IN_PROGRESS))
            .toList();
        assertThat(containers).as("2 live MI containers").hasSize(2);
        boolean bindIntact = false;
        for (ActivityEntity c : containers) {
            List<ProcessVariableEntity> rows =
                variableRepository.findByProcessInstanceIdAndScopeId(pi, c.getId()).stream()
                    .filter(v -> "activity".equals(v.getName())).toList();
            for (ProcessVariableEntity r : rows) {
                if (r.getTextValue() != null && r.getTextValue().contains("\"a1\"")) {
                    bindIntact = true;
                }
                assertThat(r.getTextValue()).as("no WRONG echo in any instance bind").doesNotContain("WRONG");
            }
        }
        assertThat(bindIntact).as("branch-a1 bind still carries a1").isTrue();

        runtimeService.completeUserTask(ut1, List.of(
            pv("decision", ProcessVariableType.STRING, "APPROVED"),
            pv("comment", ProcessVariableType.STRING, "ok-a1")));
        List<ActivityEntity> decides = activities(pi, "srvWorkTaskDecide", ActivityStatus.CREATED);
        assertThat(decides).hasSize(1);
        Map<String, String> d1 = jobVariables(pi, "srvWorkTaskDecide", decides.get(0).getId());
        assertThat(d1.get("activityUuid")).as("decide-1 carries a1, not the stale echo").isEqualTo("a1");
        assertThat(d1.get("taskUuid")).as("decide-1 carries its taskUuid").isEqualTo(taskOfBranch.get("21346/a1"));
        runtimeService.completeServiceTask(decides.get(0).getId(), List.of());

        UUID ut2 = activities(pi, "utWorkTaskExecute", ActivityStatus.CREATED).get(0).getId();
        runtimeService.completeUserTask(ut2, List.of(
            pv("decision", ProcessVariableType.STRING, "APPROVED"),
            pv("comment", ProcessVariableType.STRING, "ok-a2")));
        List<ActivityEntity> decides2 = activities(pi, "srvWorkTaskDecide", ActivityStatus.CREATED);
        assertThat(decides2).hasSize(1);
        Map<String, String> d2 = jobVariables(pi, "srvWorkTaskDecide", decides2.get(0).getId());
        assertThat(d2.get("activityUuid")).as("decide-2 carries a2").isEqualTo("a2");
        runtimeService.completeServiceTask(decides2.get(0).getId(), List.of());
        assertThat(incidents(pi)).as("no incidents").isZero();
    }

    /**
     * F-1 (security review r1, a130e522b): слой агрегации outputElement
     * ({@code ElementSupport.chainVariablesForNames}) — ВЛОЖЕННЫЙ MI-подпроцесс,
     * чей outputElement ссылается на цикл-переменную activity ВНЕШНЕГО инстанса
     * (внутренний inputElement назван item, поэтому строки activity во
     * ВНУТРЕННЕМ инстанс-скоупе нет — она живёт во внешнем инстанс-скоупе).
     * Перед входом: stale root {@code activity} (чужой employeeId) и stale root
     * {@code loopCounter=999}. Ожидание: каждый decisions[i] несёт значения
     * СВОЕГО (внешнего) инстанса и позиционный слот; stale root не используется
     * и не затирается.
     *
     * <p>На мутации MB (chainVariablesForNames → merge-ридер, строки ~891/895)
     * тест RED: merge-ридер собственного слоя добирает stale root activity для
     * имени, которого в собственном скоупе нет, и перетирает байнд внешнего
     * скоупа. Верхнеуровневый MI (outerResults) — контроль: там activity в
     * собственном скоупе инстанса, own-wins держит своё в обоих вариантах.
     *
     * <p>Дублирует зонд п.3 HOLD (miSlot — merge-ридер loopCounter,
     * MultiInstanceExecutor ~638): stale root loopCounter=999 обязан не сдвинуть
     * позиционные слоты (мута слота 998 раздула бы массивы null-ами).
     */
    @Transactional
    @Test
    void staleRoot_outputElementLoopVar_nestedMI_carriesOwnInstanceValues() throws Exception {
        UUID pdId = deployF1();
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        dto.setVariables(List.of(
            pv("outerActs", ProcessVariableType.JSON,
                "[{\"employeeId\":21346,\"uuid\":\"o1\"},{\"employeeId\":29250,\"uuid\":\"o2\"}]"),
            pv("innerActs", ProcessVariableType.JSON,
                "[{\"employeeId\":111,\"uuid\":\"i1\"},{\"employeeId\":222,\"uuid\":\"i2\"}]"),
            pv("activity", ProcessVariableType.JSON, "{\"employeeId\":99999,\"uuid\":\"stale\"}"),
            pv("loopCounter", ProcessVariableType.LONG, "999")));
        UUID pi = runtimeService.startProcessInstance(dto).getId();

        List<ActivityEntity> uts = activities(pi, "ut", ActivityStatus.CREATED);
        assertThat(uts).as("2 outer x 2 inner user tasks").hasSize(4);

        // К какому (внешний, внутренний) инстансу каждая задача — из видимого
        // контекста: activity — скоуп внешнего инстанса, item — собственный
        // внутренний. Сдача в перемешанном порядке (по id строки) — слот обязан
        // быть позиционным, не порядком завершения.
        List<ActivityEntity> ordered = new ArrayList<>(uts);
        ordered.sort(Comparator.comparing(ActivityEntity::getId));
        for (ActivityEntity ut : ordered) {
            String outerUuid = String.valueOf(visibleJson(pi, ut.getId(), "activity").get("uuid"));
            String innerUuid = String.valueOf(visibleJson(pi, ut.getId(), "item").get("uuid"));
            runtimeService.completeUserTask(ut.getId(), List.of(
                pv("decision", ProcessVariableType.STRING, "D-" + innerUuid),
                pv("comment", ProcessVariableType.STRING, "c-" + outerUuid + "-" + innerUuid)));
        }

        // decisions живёт в скоупе внешнего инстанса и удаляется с ним на
        // завершении — внешний outputElement проецирует его в outerResults
        // (root) ДО сноса: проверяем агрегаты там.
        ObjectMapper om = new ObjectMapper();
        ProcessVariableEntity outerResults = variableRepository
            .findByNameAndProcessInstanceId("outerResults", pi).orElseThrow();
        List<Map<String, Object>> orRows = om.readValue(outerResults.getTextValue(),
            new tools.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
        assertThat(orRows).as("outerResults sized by outer collection").hasSize(2);
        assertThat(orRows.stream().map(r -> String.valueOf(r.get("uuid"))).toList())
            .as("outerResults positional: slot 0 = o1, slot 1 = o2").containsExactly("o1", "o2");
        for (Map<String, Object> outer : orRows) {
            String outerUuid = String.valueOf(outer.get("uuid"));
            String outerEmployee = switch (outerUuid) {
                case "o1" -> "21346";
                case "o2" -> "29250";
                default -> throw new AssertionError("unexpected outer element " + outerUuid);
            };
            List<Map<String, Object>> decisions =
                (List<Map<String, Object>>) outer.get("decisions");
            assertThat(decisions).as("outer " + outerUuid + ": decisions sized by inner collection").hasSize(2);
            assertThat(String.valueOf(decisions.get(0).get("employee")))
                .as("outer " + outerUuid + " slot 0: employee of ITS OWN instance, not stale root").isEqualTo(outerEmployee);
            assertThat(String.valueOf(decisions.get(0).get("decision"))).as("outer " + outerUuid + " slot 0: inner item i1").isEqualTo("D-i1");
            assertThat(String.valueOf(decisions.get(0).get("comment"))).as("outer " + outerUuid + " slot 0: comment of i1").isEqualTo("c-" + outerUuid + "-i1");
            assertThat(String.valueOf(decisions.get(1).get("employee")))
                .as("outer " + outerUuid + " slot 1: employee of ITS OWN instance, not stale root").isEqualTo(outerEmployee);
            assertThat(String.valueOf(decisions.get(1).get("decision"))).as("outer " + outerUuid + " slot 1: inner item i2").isEqualTo("D-i2");
            assertThat(String.valueOf(decisions.get(1).get("comment"))).as("outer " + outerUuid + " slot 1: comment of i2").isEqualTo("c-" + outerUuid + "-i2");
        }

        // Stale root-строки нетронуты: не использованы и не перезаписаны.
        assertThat(rootRows(pi, "activity").get(0).getTextValue()).as("stale root activity untouched")
            .contains("stale");
        assertThat(rootRows(pi, "loopCounter").get(0).getTextValue()).as("stale root loopCounter untouched")
            .isEqualTo("999");
        assertThat(queryService.getProcessInstance(pi).getCompletedAt())
            .as("instance completed end-to-end").isNotNull();
        assertThat(incidents(pi)).as("no incidents").isZero();
    }
}
