package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.scheduler.TimerJobExecutor;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** C8-3: a repeating (timeCycle) non-interrupting boundary timer re-arms and fires again while the host runs. */
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

    /** Fires due timer jobs deterministically (a future bound avoids PT0S wall-clock jitter under load). */
    private void fireDueTimers() {
        dbService.findDueTimerJobs(Instant.now().plusSeconds(3600)).forEach(j -> {
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

    @Transactional
    @Test
    void repeatingNonInterruptingBoundaryTimerFiresEachCycle() throws Exception {
        // host (user task) with a non-interrupting timeCycle R/PT0S boundary -> remind. Each poll fires the
        // boundary (a remind) and re-arms the next; the host keeps running.
        String bpmn = Files.readString(Paths.get("src/test/files/test-boundary-cycle.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        UUID processInstanceId = runtimeService.startProcessInstance(dto).getId();

        fireDueTimers();
        assertThat(remindCount(processInstanceId)).isEqualTo(1);

        // re-armed: the next poll fires it again
        fireDueTimers();
        assertThat(remindCount(processInstanceId)).isEqualTo(2);

        // host is still active (non-interrupting) and another occurrence is queued
        ActivityEntity host = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId) && a.getBpmnElementId().equals("host"))
            .findFirst().orElseThrow();
        assertThat(host.getStatus()).isEqualTo(ActivityStatus.CREATED);
    }
}
