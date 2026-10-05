package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.query.ServiceTaskQuery;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ServiceTask;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.dto.IdDTO;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-34 (CR-06): {@code waitForCompletion=true} (BPMN default) parks the
 * compensation thrower until its service-task handler REALLY completes.
 * The handler is job-based (async): the throw fires it, the test completes the
 * handler job through the real completion path, and only then does the
 * continuation ({@code afterComp}) activate. Before the fix the thrower
 * completed inline and {@code afterComp} ran while the handler was still open.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class CompensationWaitIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityRepository activityRepository;

    private UUID deployAndStart() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-c834-comp-wait.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        IdDTO startResult = runtimeService.startProcessInstance(dto);
        return startResult.getId();
    }

    private ServiceTask serviceTask(UUID processInstanceId, String job) {
        ServiceTaskQuery q = new ServiceTaskQuery();
        q.setProcessInstanceId(processInstanceId);
        return queryService.findServiceTasks(q, null).getData().stream()
            .filter(t -> job.equals(t.getJob()))
            .findFirst().orElse(null);
    }

    private ActivityStatus activityStatus(UUID processInstanceId, String bpmnElementId) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .filter(a -> bpmnElementId.equals(a.getBpmnElementId()))
            .map(ActivityEntity::getStatus)
            .findFirst().orElse(null);
    }

    @Transactional
    @Test
    void waitForCompletionTrue_throwParksUntilHandlerCompletes() throws Exception {
        UUID pi = deployAndStart();

        // drive the main flow: complete the work job → throw fires the handler job
        ServiceTask work = serviceTask(pi, "comp-wait-job");
        assertThat(work).as("work job parked").isNotNull();
        runtimeService.completeServiceTask(work.getId(), List.of());

        ServiceTask handler = serviceTask(pi, "comp-handler-job");
        assertThat(handler).as("compensation handler job launched").isNotNull();

        // the thrower is PARKED (not completed) while the handler is still open …
        assertThat(activityStatus(pi, "compThrow")).isEqualTo(ActivityStatus.CREATED);
        // … and the continuation did NOT activate yet …
        assertThat(activityStatus(pi, "afterComp")).isNull();

        // completing the handler job resumes the thrower through the real path
        runtimeService.completeServiceTask(handler.getId(), List.of());

        assertThat(activityStatus(pi, "compThrow")).isEqualTo(ActivityStatus.COMPLETED);
        ProcessInstance done = queryService.getProcessInstance(pi);
        assertThat(done.getCompletedAt()).as("instance finished after compensation").isNotNull();
    }
}
