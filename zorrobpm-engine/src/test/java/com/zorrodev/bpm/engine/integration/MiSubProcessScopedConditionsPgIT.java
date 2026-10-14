package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-45 на живом PostgreSQL (V11/P-17: H2 ≠ PG): условия внутри
 * MI-инстанса/подпроцесса видят переменные скоупа. Те же 5 путей, что H2-класс
 * {@code MiSubProcessScopedConditionsIntegrationTests} (а, а-lane, б, в, г),
 * через НАСТОЯЩИЙ прод-путь (деплой → старт → complete → scope-чтения PG).
 * Путь (д) — белый ящик над H2-специфичным хелпером processFlow — на PG не
 * дублируется (тот же chain-механизм, что (а), покрыт здесь).
 */
@Tag("pg")
class MiSubProcessScopedConditionsPgIT extends PostgresIT {

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private QueryService queryService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private UserTaskRepository userTaskRepository;

    private UUID deploy(String file) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + file));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        return model.getId();
    }

    private UUID start(UUID pdId) {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        dto.setVariables(List.of());
        return runtimeService.startProcessInstance(dto).getId();
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

    private UUID userTaskByAssignee(UUID pi, String bpmnElementId, String assignee) {
        List<ActivityEntity> found = activities(pi, bpmnElementId, ActivityStatus.CREATED).stream()
            .filter(a -> assignee.equals(assigneeOf(a.getId())))
            .toList();
        assertThat(found).as(bpmnElementId + " of instance " + assignee + " (PostgreSQL)").hasSize(1);
        return found.get(0).getId();
    }

    private static ProcessVariable pv(String name, ProcessVariableType type, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(type);
        v.setValue(value);
        return v;
    }

    private void submitDecisions(UUID pi) {
        runtimeService.completeUserTask(userTaskByAssignee(pi, "utDecide", "A"),
            List.of(pv("decision", ProcessVariableType.STRING, "REJECTED")));
        runtimeService.completeUserTask(userTaskByAssignee(pi, "utDecide", "B"),
            List.of(pv("decision", ProcessVariableType.STRING, "APPROVED")));
        List<ActivityEntity> marks = activities(pi, "svcMark", ActivityStatus.CREATED);
        assertThat(marks).as("svcMark per instance (PostgreSQL)").hasSize(2);
        marks.forEach(m -> runtimeService.completeServiceTask(m.getId(), List.of()));
    }

    private void assertBranches(UUID pi, String rejectedId, String approvedId) {
        List<ActivityEntity> rejected = activities(pi, rejectedId, ActivityStatus.CREATED);
        assertThat(rejected).as("REJECTED branch of instance A (PostgreSQL)").hasSize(1);
        assertThat(assigneeOf(rejected.get(0).getId())).isEqualTo("A");
        List<ActivityEntity> approved = activities(pi, approvedId, ActivityStatus.CREATED);
        assertThat(approved).as("APPROVED branch of instance B (PostgreSQL)").hasSize(1);
        assertThat(assigneeOf(approved.get(0).getId())).isEqualTo("B");
        assertThat(incidents(pi)).as("no incidents (PostgreSQL)").isZero();
        runtimeService.completeUserTask(rejected.get(0).getId(), List.of());
        runtimeService.completeUserTask(approved.get(0).getId(), List.of());
        assertThat(queryService.getProcessInstance(pi).getCompletedAt())
            .as("instance completed (PostgreSQL)").isNotNull();
        assertThat(incidents(pi)).as("no incidents at end (PostgreSQL)").isZero();
    }

    @Test
    void exclusiveGateway_decision_routesByOwnInstance() throws Exception {
        UUID pi = start(deploy("test-c845-mi-sub-gateway-decision.bpmn"));
        submitDecisions(pi);
        assertBranches(pi, "utRejected", "utApproved");
    }

    @Test
    void exclusiveGateway_laneDecision_routesByOwnInstance() throws Exception {
        UUID pi = start(deploy("test-c845-mi-sub-gateway-lane.bpmn"));
        submitDecisions(pi);
        assertBranches(pi, "utRejected", "utApproved");
    }

    @Test
    void inclusiveSplit_decision_activatesOwnBranch() throws Exception {
        UUID pi = start(deploy("test-c845-mi-sub-inclusive-scope.bpmn"));
        runtimeService.completeUserTask(userTaskByAssignee(pi, "utDecide", "A"),
            List.of(pv("decision", ProcessVariableType.STRING, "REJECTED")));
        runtimeService.completeUserTask(userTaskByAssignee(pi, "utDecide", "B"),
            List.of(pv("decision", ProcessVariableType.STRING, "APPROVED")));
        assertBranches(pi, "utIncRejected", "utIncApproved");
    }

    @Test
    void plainSubprocess_containerInputVariable_visibleToInnerGateway() throws Exception {
        UUID pi = start(deploy("test-c845-plain-sub-gateway-scope.bpmn"));
        assertThat(activities(pi, "utInner", ActivityStatus.CREATED)).hasSize(1);
        runtimeService.completeUserTask(
            activities(pi, "utInner", ActivityStatus.CREATED).get(0).getId(), List.of());
        List<ActivityEntity> yes = activities(pi, "utYes", ActivityStatus.CREATED);
        assertThat(yes).as("gateway took the localFlag=true branch (PostgreSQL)").hasSize(1);
        assertThat(activities(pi, "utNo", ActivityStatus.CREATED)).isEmpty();
        assertThat(incidents(pi)).as("no incidents (PostgreSQL)").isZero();
        runtimeService.completeUserTask(yes.get(0).getId(), List.of());
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertThat(incidents(pi)).isZero();
    }

    @Test
    void nestedMi_completionCondition_seesContainingScopeResults() throws Exception {
        UUID pi = start(deploy("test-c845-nested-mi-completion-condition.bpmn"));
        assertThat(activities(pi, "utInner", ActivityStatus.CREATED)).hasSize(2);
        runtimeService.completeUserTask(userTaskByAssignee(pi, "utInner", "b"), List.of());
        List<ActivityEntity> after = activities(pi, "utAfter", ActivityStatus.CREATED);
        assertThat(after).as("early exit after the b-branch (PostgreSQL)").hasSize(1);
        assertThat(activities(pi, "utInner", ActivityStatus.CREATED)).isEmpty();
        assertThat(incidents(pi)).isZero();
        runtimeService.completeUserTask(after.get(0).getId(), List.of());
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertThat(incidents(pi)).isZero();
    }
}
