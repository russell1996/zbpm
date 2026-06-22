package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.scheduler.TimerJobExecutor;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
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

/** C8-3: an ISO repeating-interval timeCycle timer is parsed and fires (first occurrence) instead of raising
 *  an incident. */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class TimerCycleIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

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

    @Transactional
    @Test
    void timeCycleTimerFiresFirstOccurrence() throws Exception {
        // intermediate catch timer with timeCycle R/PT0S (immediately due). Previously a timeCycle had no
        // expression -> the timer raised an incident; now it parses and fires after the first interval.
        String bpmn = Files.readString(Paths.get("src/test/files/test-timer-cycle.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        UUID processInstanceId = runtimeService.startProcessInstance(dto).getId();

        // parked at the timer catch until the timer fires
        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNull();

        fireDueTimers();

        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNotNull();
    }
}
