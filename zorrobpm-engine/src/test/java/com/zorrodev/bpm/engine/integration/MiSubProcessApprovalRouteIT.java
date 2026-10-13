package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-40, критерий 3: полный процесс v2 из репозитория потребителя
 * ({@code test-c840-approval-route-v2.bpmn} — копия файла
 * «Workers — testApprovalRouteProcess-v2 (MI-подпроцесс, ожидает поддержку в ZBPM).bpmn»,
 * секретов нет — проверено grep по кред-паттернам при копировании).
 *
 * <p>Стенд без внешних воркеров: service task'и завершаем напрямую через
 * {@code runtimeService.completeServiceTask} (тот же путь, что worker-callback),
 * результаты подкладываем переменными завершения (слияние ответа по умолчанию).
 * Проверяется ДВИЖОК (MI-раскрутка, привязка, decide-join-gateway), а не каталоги API.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class MiSubProcessApprovalRouteIT {

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

    private UUID deploy() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-c840-approval-route-v2.bpmn"));
        return processDefinitionService.addProcessDefinition(bpmn).getId();
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

    /** Payload-переменные outbox-джобы service task (проверка chain-payload: assigneeEmployeeId). */
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
        // variables — Map<String, exchange.ProcessVariable{name,value,type}>:
        // Jackson пишет Map как {"name": {...}} — итерируем propertyNames
        // (тот же приём, что serviceTaskHeaders в C8-parity тестах).
        Map<String, String> result = new java.util.LinkedHashMap<>();
        var varsNode = om.readTree(entries.get(0).getPayload()).get("variables");
        var names = varsNode.propertyNames().iterator();
        while (names.hasNext()) {
            String name = names.next();
            result.put(name, varsNode.get(name).get("value").asText());
        }
        return result;
    }

    /**
     * Критерий 3: два исполнителя — две параллельные джобы srvWorkTaskCreate
     * со СВОИМ assigneeEmployeeId (21346/29250, без перекрёста), две user task,
     * сдача первой → srvWorkTaskDecide по её ветке сразу, сдача второй → join
     * и decisions в порядке инстансов, все APPROVED → возврат на опрос.
     */
    @Transactional
    @Test
    void approvalRoute_twoAssignees_decideJoinAndApprovedLoop() throws Exception {
        UUID pdId = deploy();
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        dto.setVariables(List.of(
            pv("createdEmployeeId", ProcessVariableType.LONG, "100"),
            pv("routeUuid", ProcessVariableType.STRING, "route-1"),
            pv("processTypeId", ProcessVariableType.LONG, "4"),
            pv("sourceUuid", ProcessVariableType.STRING, "src-1"),
            pv("sourceVersion", ProcessVariableType.LONG, "1"),
            pv("stages", ProcessVariableType.JSON, "[]"),
            pv("resourceUuid", ProcessVariableType.STRING, "res-1")));
        UUID pi = runtimeService.startProcessInstance(dto).getId();

        // Setup-ветка: закрываем параллельный fork тоже (иначе join setup висит).
        // Порядок: pgwRouteSetupFork → 3 service task → join → srvRouteStart → опрос.
        // Результаты подкладываем переменными завершения (слияние ответа воркера).
        for (String setup : List.of("srvRouteCreate", "srvRouteSourceMapLink",
            "srvStageCreateWithActivities", "srvResourceCreate")) {
            List<ActivityEntity> jobs = activities(pi, setup, ActivityStatus.CREATED);
            assertThat(jobs).as("setup job " + setup).hasSize(1);
            if ("srvRouteCreate".equals(setup)) {
                runtimeService.completeServiceTask(jobs.get(0).getId(),
                    List.of(pv("routeUuid", ProcessVariableType.STRING, "route-1")));
            } else if ("srvResourceCreate".equals(setup)) {
                runtimeService.completeServiceTask(jobs.get(0).getId(),
                    List.of(pv("resourceUuid", ProcessVariableType.STRING, "res-1")));
            } else {
                runtimeService.completeServiceTask(jobs.get(0).getId(), List.of());
            }
        }
        List<ActivityEntity> starters = activities(pi, "srvRouteStart", ActivityStatus.CREATED);
        assertThat(starters).hasSize(1);
        runtimeService.completeServiceTask(starters.get(0).getId(), List.of());
        // Опрос: подкладываем verified-набор выходов (routeStageUuid/stageModeId/
        // activities/hasOpen/hasRejected) — движок дальше ведёт по hasOpen=true.
        List<ActivityEntity> polls = activities(pi, "srvRouteOpenActivities", ActivityStatus.CREATED);
        assertThat(polls).hasSize(1);
        runtimeService.completeServiceTask(polls.get(0).getId(), List.of(
            pv("routeStageUuid", ProcessVariableType.STRING, "stage-1"),
            pv("stageModeId", ProcessVariableType.LONG, "2"),
            pv("activities", ProcessVariableType.JSON,
                "[{\"employeeId\":21346,\"uuid\":\"act-1\"},{\"employeeId\":29250,\"uuid\":\"act-2\"}]"),
            pv("hasOpen", ProcessVariableType.BOOLEAN, "true"),
            pv("hasRejected", ProcessVariableType.BOOLEAN, "false")));

        // Вход в MI-подпроцесс: две параллельные джобы srvWorkTaskCreate.
        List<ActivityEntity> creates = activities(pi, "srvWorkTaskCreate", ActivityStatus.CREATED);
        assertThat(creates).as("2 parallel srvWorkTaskCreate (one per assignee)").hasSize(2);

        // Chain-payload: каждая джоба несёт assigneeEmployeeId СВОЕГО activity.
        // Точное сопоставление джоба→assignee: payload каждой джобы отдельно.
        ObjectMapper om = new ObjectMapper();
        List<String> assignees = new ArrayList<>();
        for (ActivityEntity job : creates) {
            Map<String, String> vars = jobVariables(pi, "srvWorkTaskCreate", job.getId());
            assertThat(vars).as("create job payload carries assigneeEmployeeId").containsKey("assigneeEmployeeId");
            assignees.add(vars.get("assigneeEmployeeId"));
        }
        assertThat(assignees).as("each create job carries its own assigneeEmployeeId, no cross")
            .containsExactlyInAnyOrder("21346", "29250");

        // Завершаем create-джобы: taskUuid per-branch (слияние ответа).
        for (int i = 0; i < creates.size(); i++) {
            runtimeService.completeServiceTask(creates.get(i).getId(),
                List.of(pv("taskUuid", ProcessVariableType.STRING, "task-" + (i + 1))));
        }

        // Две параллельные user task с assignee СВОЕГО activity.
        List<ActivityEntity> userTasks = activities(pi, "utWorkTaskExecute", ActivityStatus.CREATED);
        assertThat(userTasks).as("2 parallel user tasks").hasSize(2);
        assertThat(userTasks.stream().map(a -> assigneeOf(a.getId())).toList())
            .containsExactlyInAnyOrder("21346", "29250");

        // Сдача ПЕРВОЙ → srvWorkTaskDecide по её ветке сразу, вторая ждёт.
        runtimeService.completeUserTask(userTasks.get(0).getId(), List.of(
            pv("decision", ProcessVariableType.STRING, "APPROVED"),
            pv("comment", ProcessVariableType.STRING, "ok-1")));
        List<ActivityEntity> decides = activities(pi, "srvWorkTaskDecide", ActivityStatus.CREATED);
        assertThat(decides).as("decide job of the completed branch starts immediately").hasSize(1);
        assertThat(activities(pi, "utWorkTaskExecute", ActivityStatus.CREATED))
            .as("second branch still waits").hasSize(1);

        // Decide-джоба первой ветки пишет решение; завершаем её.
        runtimeService.completeServiceTask(decides.get(0).getId(), List.of());

        // Сдача ВТОРОЙ → join, decisions в порядке инстансов.
        List<ActivityEntity> remaining = activities(pi, "utWorkTaskExecute", ActivityStatus.CREATED);
        assertThat(remaining).hasSize(1);
        runtimeService.completeUserTask(remaining.get(0).getId(), List.of(
            pv("decision", ProcessVariableType.STRING, "APPROVED"),
            pv("comment", ProcessVariableType.STRING, "ok-2")));
        List<ActivityEntity> decides2 = activities(pi, "srvWorkTaskDecide", ActivityStatus.CREATED);
        assertThat(decides2).as("decide job of the second branch").hasSize(1);
        runtimeService.completeServiceTask(decides2.get(0).getId(), List.of());

        ProcessVariableEntity decisions = variableRepository
            .findByNameAndProcessInstanceId("decisions", pi).orElseThrow();
        List<Map<String, Object>> rows = om.readValue(decisions.getTextValue(),
            new tools.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
        assertThat(rows).as("decisions aggregated in instance order").hasSize(2);
        assertThat(rows.stream().map(r -> String.valueOf(r.get("decision"))).toList())
            .containsExactly("APPROVED", "APPROVED");

        // Все APPROVED → gwWorkTaskDecision ведёт назад на опрос (flowWorkTaskApproved).
        List<ActivityEntity> polls2 = activities(pi, "srvRouteOpenActivities", ActivityStatus.CREATED);
        assertThat(polls2).as("all APPROVED returns to open-activities poll").hasSize(1);
        assertThat(incidents(pi)).as("no incidents").isZero();
    }
}
