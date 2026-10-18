package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.query.IncidentQuery;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-C8-49 (PG-сторона): kill-пути инстанса (отмена, terminate) закрывают открытый
 * инцидент и переводят ERROR-активность в CANCELLED через единую финализацию;
 * resolve на закрытом инстансе — 409 без перезапуска. H2-близнец —
 * {@code FinishedInstanceIncidentsIntegrationTests}.
 *
 * <p>PG обязателен: финализация ходит через bulk-UPDATE строк (errorActivity/
 * cancelActivity — UPDATE ... WHERE id) и повторные чтения в той же транзакции —
 * на H2 snapshot-поведение отличается (класс pg-vs-h2-divergence).
 */
public class FinishedInstanceIncidentsPgIT extends PostgresIT {

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private QueryService queryService;
    @Autowired private DBService dbService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private ProcessInstanceRepository processInstanceRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        jdbc.execute("DELETE FROM timer_jobs");
        jdbc.execute("DELETE FROM message_subscriptions");
        jdbc.execute("DELETE FROM signal_subscriptions");
        jdbc.execute("DELETE FROM incidents");
        jdbc.execute("DELETE FROM user_task_candidates");
        jdbc.execute("DELETE FROM user_tasks");
        jdbc.execute("DELETE FROM service_tasks");
        jdbc.execute("DELETE FROM variables");
        jdbc.execute("DELETE FROM activities");
        jdbc.execute("DELETE FROM tokens");
        jdbc.execute("DELETE FROM events");
        jdbc.execute("DELETE FROM process_instances");
        jdbc.execute("DELETE FROM process_definitions WHERE code LIKE 'test-%'");
    }

    private UUID deployAndStart(String bpmnFile) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + bpmnFile));
        return tx.execute(s -> {
            ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
            StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
            dto.setProcessDefinitionId(model.getId());
            return runtimeService.startProcessInstance(dto).getId();
        });
    }

    private UUID activeActivityId(UUID pi, String elementId) {
        return tx.execute(s -> activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getBpmnElementId().equals(elementId)
                && (a.getStatus() == ActivityStatus.CREATED || a.getStatus() == ActivityStatus.IN_PROGRESS))
            .map(ActivityEntity::getId)
            .findFirst().orElseThrow());
    }

    private long errorCount(UUID pi) {
        return tx.execute(s -> activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getStatus() == ActivityStatus.ERROR)
            .count());
    }

    private long openIncidentCount(UUID pi) {
        IncidentQuery q = new IncidentQuery();
        q.setProcessInstanceId(pi);
        q.setResolved(false);
        return tx.execute(s -> (long) queryService.findIncidents(q, null).getData().size());
    }

    @Test
    void cancelWithOpenIncident_closesIncidentAndErrorActivity() throws Exception {
        UUID pi = deployAndStart("test-service-task-fail.bpmn");
        UUID svc = activeActivityId(pi, "svc");
        tx.executeWithoutResult(s -> runtimeService.failServiceTask(svc, "boom", 0));
        assertThat(openIncidentCount(pi)).isEqualTo(1);
        assertThat(errorCount(pi)).isEqualTo(1);

        tx.executeWithoutResult(s -> {
            dbService.cancelActiveActivities(pi);
            dbService.deleteTimerJobsByProcessInstanceId(pi);
            dbService.deleteMessageSubscriptionsByProcessInstanceId(pi);
            dbService.cancelProcessInstance(pi);
        });

        assertThat(openIncidentCount(pi)).as("открытый инцидент закрыт отменой").isZero();
        assertThat(errorCount(pi)).as("ERROR переведена в CANCELLED").isZero();
        Object completedAt = tx.execute(s -> dbService.getProcessInstance(pi).getCompletedAt());
        assertThat(completedAt).isNotNull();
    }

    @Test
    void terminateWithOpenIncident_closesLeftovers() throws Exception {
        UUID pi = deployAndStart("test-c849-terminate-with-open-incident.bpmn");
        UUID svc = activeActivityId(pi, "svc");
        UUID taskB = activeActivityId(pi, "taskB");
        tx.executeWithoutResult(s -> runtimeService.failServiceTask(svc, "boom", 0));
        assertThat(openIncidentCount(pi)).isEqualTo(1);
        assertThat(errorCount(pi)).isEqualTo(1);

        tx.executeWithoutResult(s -> runtimeService.completeUserTask(taskB, List.of()));

        Object completedAt = tx.execute(s -> dbService.getProcessInstance(pi).getCompletedAt());
        assertThat(completedAt).as("terminate завершает инстанс").isNotNull();
        assertThat(openIncidentCount(pi)).as("открытый инцидент закрыт terminate-выходом").isZero();
        assertThat(errorCount(pi)).as("ERROR переведена в CANCELLED").isZero();
    }

    @Test
    void resolveOnFinishedInstance_conflictsWithoutReexecution() throws Exception {
        UUID pi = deployAndStart("test-service-task-fail.bpmn");
        UUID svc = activeActivityId(pi, "svc");
        tx.executeWithoutResult(s -> runtimeService.failServiceTask(svc, "boom", 0));
        IncidentQuery q = new IncidentQuery();
        q.setProcessInstanceId(pi);
        q.setResolved(false);
        UUID incidentId = tx.execute(
            s -> queryService.findIncidents(q, null).getData().get(0).getId());
        long svcBefore = tx.execute(s -> activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getBpmnElementId().equals("svc"))
            .count());
        // Legacy-состояние: инстанс закрыт без финализации.
        tx.executeWithoutResult(s -> {
            processInstanceRepository.setCancelled(pi, true);
            processInstanceRepository.setCompletedAt(pi, Instant.now());
        });

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> runtimeService.resolveIncident(incidentId, List.of())))
            .isInstanceOf(ResponseStatusException.class);

        long svcAfter = tx.execute(s -> activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getBpmnElementId().equals("svc"))
            .count());
        assertThat(svcAfter).as("новых активностей нет").isEqualTo(svcBefore);
        assertThat(openIncidentCount(pi)).as("инцидент остался открыт (legacy)").isEqualTo(1);
    }
}
