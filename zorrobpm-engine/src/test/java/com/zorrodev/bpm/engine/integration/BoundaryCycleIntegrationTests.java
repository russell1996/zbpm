package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.TimerJobEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.TimerJobRepository;
import com.zorrodev.bpm.engine.scheduler.TimerJobExecutor;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;

import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C8-3: a repeating (timeCycle) non-interrupting boundary timer re-arms and fires again while the host runs.
 *
 * WO-REL-13 (R-03): each step runs in its OWN committed transaction — timer fires (TimerJobExecutor
 * REQUIRES_NEW) cannot see rows of the test's outer transaction (READ COMMITTED).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class BoundaryCycleIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private DBService dbService;

    @Autowired
    private TimerJobExecutor timerJobExecutor;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private TimerJobRepository timerJobRepository;

    private UUID createdProcessInstanceId;

    /**
     * WO-REL-13: plain timer jobs (intermediate catch / boundary / cycle) are created WITHOUT a
     * processInstanceId (only event-subprocess timer jobs carry one), so per-instance cleanup and
     * filtering is impossible. Instead we scope by creation time: every job of this test is created
     * after testStartedAt, foreign jobs are strictly older. @AfterEach deletes the whole window.
     */
    private Instant testStartedAt;

    /** Runs the action in its own committed transaction (WO-REL-13: fires must see committed rows). */
    private void inNewTx(Runnable action) {
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tt.execute(status -> {
            action.run();
            return null;
        });
    }

    @AfterEach
    void cleanup() {
        // WO-REL-13: tests commit their own rows now (REQUIRES_NEW fires), so the shared H2
        // database must be cleaned explicitly — otherwise other tests see stale timer jobs
        // (TimerMessageQuery counts fired=true rows; ControlProcess/MiReenter expect an exact
        // findAll() size). Delete the whole creation-time window, including un-fired re-arms.
        Instant since = testStartedAt;
        inNewTx(() -> {
            List<TimerJobEntity> mine = timerJobRepository.findAll().stream()
                .filter(e -> e.getCreatedAt() != null && !e.getCreatedAt().isBefore(since))
                .toList();
            timerJobRepository.deleteAll(mine);
        });
        createdProcessInstanceId = null;
    }

    /**
     * Fires due timer jobs created by THIS test only (WO-REL-13: REQUIRES_NEW fire commits
     * per-job, so firing jobs of other tests would leave fired=true rows that pollute the shared
     * H2 database). Scoped by creation time: every job of this test is created after the moment
     * the test started, while foreign jobs are strictly older.
     */
    private void fireDueTimers() {
        dbService.findDueTimerJobs(Instant.now().plusSeconds(3600)).stream()
            .filter(j -> j.getCreatedAt() != null && !j.getCreatedAt().isBefore(testStartedAt))
            .forEach(j -> {
                try {
                    timerJobExecutor.fire(j);
                } catch (Exception ignored) {
                    // isolate unrelated jobs, mirroring TimerScheduler
                }
            });
    }

    private long remindCount(UUID processInstanceId) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .filter(a -> a.getBpmnElementId().equals("remind") && a.getStatus() == ActivityStatus.COMPLETED)
            .count();
    }

    @Test
    void repeatingNonInterruptingBoundaryTimerFiresEachCycle() throws Exception {
        testStartedAt = Instant.now();
        // host (user task) with a non-interrupting timeCycle R/PT0S boundary -> remind. Each poll fires the
        // boundary (a remind) and re-arms the next; the host keeps running.
        String bpmn = Files.readString(Paths.get("src/test/files/test-boundary-cycle.bpmn"));

        UUID[] processInstanceId = new UUID[1];
        inNewTx(() -> {
            ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
            StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
            dto.setProcessDefinitionId(model.getId());
            processInstanceId[0] = runtimeService.startProcessInstance(dto).getId();
        });
        createdProcessInstanceId = processInstanceId[0];

        inNewTx(this::fireDueTimers);
        assertThat(remindCount(processInstanceId[0])).isEqualTo(1);

        // re-armed: the next poll fires it again
        inNewTx(this::fireDueTimers);
        assertThat(remindCount(processInstanceId[0])).isEqualTo(2);

        // host is still active (non-interrupting) and another occurrence is queued
        ActivityEntity host = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId[0]) && a.getBpmnElementId().equals("host"))
            .findFirst().orElseThrow();
        assertThat(host.getStatus()).isEqualTo(ActivityStatus.CREATED);
    }
}
