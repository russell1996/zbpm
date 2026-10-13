package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-40, критерий 1 на живом PostgreSQL (V11/P-17: H2 ≠ PG): минимальный процесс
 * из отчёта потребителя — 3 параллельные задачи MI-подпроцесса, assignee a/b/c,
 * после сдачи всех {@code results=["a","b","c"]}, процесс завершён, инцидентов 0.
 *
 * <p>Идёт через НАСТОЯЩИЙ прод-путь (деплой → старт → completeUserTask → агрегация
 * {@code setJsonElementAt} по диалекту PG → join), откат MI-входа подпроцесса
 * обязан ронять этот тест (POF дословно в отчёте).
 */
@Tag("pg")
class MiSubProcessMinimalPgIT extends PostgresIT {

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private QueryService queryService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private VariableRepository variableRepository;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private UserTaskRepository userTaskRepository;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void parallelMinimal_threeTasksAndPositionalResults() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-c840-mi-sub-minimal.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of());
        UUID pi = runtimeService.startProcessInstance(dto).getId();

        List<ActivityEntity> tasks = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> "utProbe".equals(a.getBpmnElementId()) && a.getStatus() == ActivityStatus.CREATED)
            .toList();
        assertThat(tasks).as("3 parallel MI-subprocess instances (PostgreSQL)").hasSize(3);
        assertThat(tasks.stream()
            .map(a -> userTaskRepository.findById(a.getId()).map(u -> u.getAssignee()).orElse(null))
            .toList()).containsExactlyInAnyOrder("a", "b", "c");

        tasks.forEach(t -> runtimeService.completeUserTask(t.getId(), List.of()));

        assertThat(queryService.getProcessInstance(pi).getCompletedAt())
            .as("instance completed (PostgreSQL)").isNotNull();

        String stored = jdbc.queryForObject(
            "SELECT text_value FROM variables WHERE process_instance_id = ? AND name = ? AND scope_id IS NULL",
            String.class, pi, "results");
        List<String> values = new ObjectMapper().readValue(stored,
            new tools.jackson.core.type.TypeReference<List<String>>() {});
        assertThat(values).as("results=[a,b,c] in instance order (PostgreSQL)").containsExactly("a", "b", "c");

        var activityIds = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .map(ActivityEntity::getId)
            .collect(Collectors.toSet());
        assertThat(incidentRepository.findAll().stream()
            .filter(i -> activityIds.contains(i.getActivityId()))
            .count()).as("no incidents (PostgreSQL)").isZero();
    }
}
