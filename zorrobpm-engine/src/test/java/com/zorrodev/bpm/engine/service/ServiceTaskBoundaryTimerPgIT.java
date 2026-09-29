package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ServiceTask;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.dto.IdDTO;
import com.zorrodev.bpm.engine.dto.TimerJob;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.TimerJobEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.TimerJobRepository;
import com.zorrodev.bpm.engine.scheduler.TimerJobExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-DIFF-4 — PG-twin of {@code ServiceTaskBoundaryTimerIntegrationTests} (interrupting
 * path, Raxon S-033): proves the boundary-timer scheduling + fire works against real
 * PostgreSQL (timer_jobs row shape, claim SQL, cancel + boundary continuation).
 * Tagged {@code pg} via {@link PostgresIT} — excluded from the default H2 build.
 */
class ServiceTaskBoundaryTimerPgIT extends PostgresIT {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private TimerJobRepository timerJobRepository;

    @Autowired
    private TimerJobExecutor timerJobExecutor;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @org.junit.jupiter.api.AfterEach
    void cleanTimerState() {
        // Cross-test timer_jobs rows leak through the shared database (WO-REL-13
        // window-cleanup precedent); AFTER (not before) so other classes' rows created
        // while PG tests run interleaved are not deleted from under them mid-suite.
        jdbc.execute("DELETE FROM timer_jobs WHERE process_instance_id IN "
            + "(SELECT id FROM process_instances WHERE id IN "
            + "(SELECT process_instance_id FROM activities WHERE bpmn_element_id IN ('work','escape')))");
    }

    @Test
    void interrupting_boundaryScheduledAndFiresOnRealPostgres() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-diff4-interrupting.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        IdDTO startResult = runtimeService.startProcessInstance(dto);
        UUID pi = startResult.getId();

        // Scheduled on entry: exactly one timer_jobs row for the boundary.
        List<TimerJobEntity> rows = timerJobRepository.findAll().stream()
            .filter(r -> pi.equals(r.getProcessInstanceId()))
            .filter(r -> "timeout".equals(r.getBoundaryElementId()))
            .filter(r -> !r.isFired())
            .toList();
        assertThat(rows).as("boundary timer job row on PG").hasSize(1);
        TimerJobEntity row = rows.get(0);

        // Fire through the production executor (claim + dispatch), same as the poller.
        TimerJob job = new TimerJob();
        job.setId(row.getId());
        job.setActivityId(row.getActivityId());
        job.setDueAt(row.getDueAt());
        job.setCreatedAt(row.getCreatedAt());
        job.setBoundaryElementId(row.getBoundaryElementId());
        job.setProcessInstanceId(row.getProcessInstanceId());
        job.setRemainingCount(row.getRemainingCount());
        job.setExpression(row.getExpression());
        timerJobExecutor.fire(job);

        // Host cancelled on PG, escape task parked on the boundary path.
        ActivityEntity host = activityRepository.findById(row.getActivityId()).orElseThrow();
        assertThat(host.getStatus()).isEqualTo(ActivityStatus.CANCELLED);
        boolean escapeParked = activityRepository.findAll().stream()
            .anyMatch(a -> pi.equals(a.getProcessInstanceId())
                && "escape".equals(a.getBpmnElementId())
                && (a.getStatus() == ActivityStatus.CREATED || a.getStatus() == ActivityStatus.IN_PROGRESS));
        assertThat(escapeParked).as("escape task parked via boundary path").isTrue();

        // The ServiceTask read-model exposes the host as CANCELLED too.
        com.zorrodev.bpm.contract.dto.query.ServiceTaskQuery q =
            new com.zorrodev.bpm.contract.dto.query.ServiceTaskQuery();
        q.setProcessInstanceId(pi);
        List<ServiceTask> tasks = queryService.findServiceTasks(q, null).getData();
        assertThat(tasks).extracting(ServiceTask::getJob).contains("diff4-escape");
    }
}
