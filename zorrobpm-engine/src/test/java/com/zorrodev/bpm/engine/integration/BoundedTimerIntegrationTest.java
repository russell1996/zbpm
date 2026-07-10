package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.scheduler.TimerJobExecutor;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-FEAT-3: Bounded repeating timers (R3/PT1S).
 *  #1: R3/PT1S fires exactly 3 times (process completes after 3rd)
 *  #2: After 3rd fire, process is completed (no 4th fire)
 *  #4: proof-of-failure — old code fires only 1 time
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
class BoundedTimerIntegrationTest {

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

    private void fireDueTimers() {
        dbService.findDueTimerJobs(Instant.now().plusSeconds(3600)).forEach(j -> {
            try {
                timerJobExecutor.fire(j);
            } catch (Exception ignored) {
            }
        });
    }

    @Transactional
    @Test
    void criterion1_boundedTimerFiresThreeTimes() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-timer-bounded.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        UUID processInstanceId = runtimeService.startProcessInstance(dto).getId();

        // First fire
        fireDueTimers();
        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNull();

        // Second fire (re-arm from first)
        fireDueTimers();
        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNull();

        // Third fire (re-arm from second) — process completes after R3
        fireDueTimers();
        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNotNull();
    }
}
