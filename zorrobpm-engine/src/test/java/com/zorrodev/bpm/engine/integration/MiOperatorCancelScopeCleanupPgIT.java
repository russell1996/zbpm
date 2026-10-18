package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-46 на живом PostgreSQL (V11/P-17: H2 ≠ PG — bulk DELETE и ON CONFLICT
 * upsert-пути диалектно разные): операторская отмена процесса с живым
 * MI-подпроцессом чистит скоуп-переменные инстансов и предсозданный
 * outputCollection тем же механизмом, что нормальное завершение, а
 * root-переменные не трогает. Идёт через НАСТОЯЩИЙ прод-путь
 * ({@code DBService.cancelProcessInstance} — единый choke-point отмены).
 */
@Tag("pg")
class MiOperatorCancelScopeCleanupPgIT extends PostgresIT {

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private DBService dbService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private VariableRepository variableRepository;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @Transactional
    void operatorCancel_cleansMiScopeVariablesAndBodyState() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-c840-mi-sub-minimal.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        ProcessVariable rootVar = new ProcessVariable();
        rootVar.setName("orderId");
        rootVar.setValue("o-1");
        rootVar.setType(ProcessVariableType.STRING);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of(rootVar));
        UUID pi = runtimeService.startProcessInstance(dto).getId();

        List<ActivityEntity> tasks = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> "utProbe".equals(a.getBpmnElementId()) && a.getStatus() == ActivityStatus.CREATED)
            .toList();
        assertThat(tasks).as("3 live inner tasks before cancel (PostgreSQL)").hasSize(3);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM variables WHERE process_instance_id = ? AND scope_id IS NOT NULL",
            Long.class, pi)).as("scope rows exist before cancel (PostgreSQL)").isEqualTo(6L);

        // Тот же порядок, что операторский путь (без authz/canceling-фазы).
        dbService.cancelActiveActivities(pi);
        dbService.deleteTimerJobsByProcessInstanceId(pi);
        dbService.deleteMessageSubscriptionsByProcessInstanceId(pi);
        dbService.cancelProcessInstance(pi);

        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM variables WHERE process_instance_id = ? AND scope_id IS NOT NULL",
            Long.class, pi)).as("no scope variables left after operator cancel (PostgreSQL)").isZero();
        assertThat(variableRepository.findByNameAndProcessInstanceId("results", pi))
            .as("precreated outputCollection dropped (PostgreSQL)").isEmpty();
        assertThat(variableRepository.findByNameAndProcessInstanceId("_mi_batch_subProbe", pi))
            .as("batch marker dropped (PostgreSQL)").isEmpty();
        assertThat(variableRepository.findByNameAndProcessInstanceId("orderId", pi))
            .as("root variable preserved (PostgreSQL)").isPresent();
    }
}
