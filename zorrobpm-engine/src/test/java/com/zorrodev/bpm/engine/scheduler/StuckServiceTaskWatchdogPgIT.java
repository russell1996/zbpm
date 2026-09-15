package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("pg")
public class StuckServiceTaskWatchdogPgIT extends PostgresIT {

    @Autowired ProcessDefinitionService processDefinitionService;
    @Autowired RuntimeService runtimeService;
    @Autowired ActivityRepository activityRepository;
    @Autowired IncidentRepository incidentRepository;
    @Autowired StuckServiceTaskWatchdog watchdog;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JdbcTemplate jdbcTemplate;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        // default for tests: 5m timeout, 1s interval not relevant (we call directly)
        watchdog.setDispatchTimeout(Duration.ofMinutes(5));
        watchdog.setBatchSize(100);
        // clean global stuck state leaked from previous tests (idempotent watchdog scans all)
        tx.executeWithoutResult(s -> {
            jdbcTemplate.update("DELETE FROM incidents");
            jdbcTemplate.update("UPDATE activities SET created_at = now() WHERE type = 'SERVICE_TASK' AND status = 'CREATED'");
        });
    }

    private UUID startServiceTaskProcess() {
        return tx.execute(s -> {
            try {
                String bpmn = Files.readString(Paths.get("src/test/files/test3.bpmn"));
                ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
                StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
                dto.setProcessDefinitionId(model.getId());
                return runtimeService.startProcessInstance(dto).getId();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private UUID firstCreatedId(UUID pi, String elementId) {
        return tx.execute(s -> activityRepository.findAll().stream()
                .filter(a -> a.getProcessInstanceId().equals(pi)
                        && a.getBpmnElementId().equals(elementId)
                        && a.getStatus() == ActivityStatus.CREATED)
                .map(a -> a.getId())
                .findFirst().orElse(null));
    }

    private void shiftCreatedAt(UUID activityId, Duration ago) {
        Instant past = Instant.now().minus(ago);
        tx.executeWithoutResult(s -> jdbcTemplate.update(
                "UPDATE activities SET created_at = ? WHERE id = ?", java.sql.Timestamp.from(past), activityId));
    }

    private long countOpenIncidents(UUID activityId) {
        return tx.execute(s -> (long) incidentRepository.findByActivityIdInAndCompletedAtIsNull(List.of(activityId)).size());
    }

    @Test
    void stuckTask_triggersIncident() {
        UUID pi = startServiceTaskProcess();
        UUID serviceTaskId = firstCreatedId(pi, "serviceTask1");
        assertThat(serviceTaskId).isNotNull();

        // not stuck yet — should not trigger (WO-REL-35: real @Scheduled path,
        // no TransactionTemplate wrapper — the wrapper masked F08)
        watchdog.checkStuckTasks();
        assertThat(countOpenIncidents(serviceTaskId)).isEqualTo(0L);

        // make it stuck: shift 10m ago (>5m timeout)
        shiftCreatedAt(serviceTaskId, Duration.ofMinutes(10));

        watchdog.checkStuckTasks();
        assertThat(countOpenIncidents(serviceTaskId)).isEqualTo(1L);

        // incident message contains dispatch-timeout marker
        String msg = tx.execute(s -> incidentRepository.findByActivityIdInAndCompletedAtIsNull(List.of(serviceTaskId)).get(0).getMessage());
        assertThat(msg).contains("SERVICE_TASK_DISPATCH_TIMEOUT");
    }

    @Test
    void stuckTask_idempotentSecondRunDoesNotDuplicate() {
        UUID pi = startServiceTaskProcess();
        UUID serviceTaskId = firstCreatedId(pi, "serviceTask1");
        shiftCreatedAt(serviceTaskId, Duration.ofMinutes(10));

        watchdog.checkStuckTasks();
        assertThat(countOpenIncidents(serviceTaskId)).isEqualTo(1L);

        watchdog.checkStuckTasks();
        assertThat(countOpenIncidents(serviceTaskId)).isEqualTo(1L);
    }

    @Test
    void disabled_whenTimeoutZero_doesNotRaise() {
        watchdog.setDispatchTimeout(Duration.ZERO);
        UUID pi = startServiceTaskProcess();
        UUID serviceTaskId = firstCreatedId(pi, "serviceTask1");
        shiftCreatedAt(serviceTaskId, Duration.ofMinutes(10));

        watchdog.checkStuckTasks();
        assertThat(countOpenIncidents(serviceTaskId)).isEqualTo(0L);
    }

    @Test
    void notStuck_recentTask_doesNotRaise() {
        UUID pi = startServiceTaskProcess();
        UUID serviceTaskId = firstCreatedId(pi, "serviceTask1");
        // created just now, well within 5m
        watchdog.checkStuckTasks();
        assertThat(countOpenIncidents(serviceTaskId)).isEqualTo(0L);
    }
}
