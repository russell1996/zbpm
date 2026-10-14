package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-44, критерий 1 на живом PostgreSQL (V11/P-17: H2 ≠ PG): тот же сценарий,
 * что {@code MiSubProcessUndeclaredOutgoingIntegrationTests} — точный XML
 * консьюмера без объявленного {@code <bpmn:outgoing>}, ранний выход по
 * {@code completionCondition} завершает процесс.
 *
 * <p>Идёт через НАСТОЯЩИЙ прод-путь (деплой → старт → completeUserTask →
 * ранний выход → proceedToOutgoing по нормализованным ссылкам).
 */
@Tag("pg")
class MiSubProcessUndeclaredOutgoingPgIT extends PostgresIT {

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private QueryService queryService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private VariableRepository variableRepository;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.zorrodev.bpm.engine.handler.ElementSupport elementSupport;

    private Map<String, String> visible(UUID pi, UUID activityId) {
        Map<String, String> result = new LinkedHashMap<>();
        for (ProcessVariable v : elementSupport.visibleVariables(pi, activityId)) {
            result.put(v.getName(), v.getValue());
        }
        return result;
    }

    @Test
    void earlyExitWithoutDeclaredOutgoing_completesProcess() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-c844-mi-sub-no-declared-outgoing.bpmn"));
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

        ActivityEntity winner = tasks.stream()
            .filter(a -> "b".equals(visible(pi, a.getId()).get("item")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No utProbe instance with item=b"));
        runtimeService.completeUserTask(winner.getId(), List.of());

        assertThat(queryService.getProcessInstance(pi).getCompletedAt())
            .as("instance completed (PostgreSQL)").isNotNull();

        String stored = jdbc.queryForObject(
            "SELECT text_value FROM variables WHERE process_instance_id = ? AND name = ? AND scope_id IS NULL",
            String.class, pi, "results");
        List<String> values = new ObjectMapper().readValue(stored,
            new tools.jackson.core.type.TypeReference<List<String>>() {});
        assertThat(values).as("results=[null,b,null] (PostgreSQL)")
            .containsExactly(null, "b", null);

        assertThat(activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED
                || a.getStatus() == ActivityStatus.IN_PROGRESS)
            .toList()).as("no live activities remain (PostgreSQL)").isEmpty();

        var activityIds = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .map(ActivityEntity::getId)
            .collect(Collectors.toSet());
        assertThat(incidentRepository.findAll().stream()
            .filter(i -> activityIds.contains(i.getActivityId()))
            .count()).as("no incidents (PostgreSQL)").isZero();
    }
}
