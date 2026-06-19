package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
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

@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class EscalationIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityRepository activityRepository;

    private List<ActivityEntity> activities(UUID processInstanceId) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .toList();
    }

    private UUID start(String fixture) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + fixture));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        IdDTO startResult = runtimeService.startProcessInstance(dto);
        return startResult.getId();
    }

    @Transactional
    @Test
    void nonInterruptingEscalationRunsBoundaryBranchAndLetsSubProcessComplete() throws Exception {
        // escalation end inside the sub-process raises ESC-1; a non-interrupting boundary spawns a
        // parallel branch (-> boundaryEnd) while the sub-process still completes normally (-> mainEnd).
        UUID processInstanceId = start("test-escalation-noninterrupting.bpmn");

        ProcessInstance pi = queryService.getProcessInstance(processInstanceId);
        assertThat(pi.getCompletedAt()).isNotNull();

        List<ActivityEntity> activities = activities(processInstanceId);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("boundaryEnd") && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("mainEnd") && a.getStatus() == ActivityStatus.COMPLETED);
        // the sub-process is not cancelled by a non-interrupting escalation
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("sub1") && a.getStatus() == ActivityStatus.COMPLETED);
    }

    @Transactional
    @Test
    void interruptingEscalationCancelsSubProcessAndTakesBoundaryPath() throws Exception {
        // an escalation end inside the sub-process raises ESC-1; an interrupting boundary cancels the
        // sub-process and continues from the boundary, so the sub-process's normal outgoing is abandoned.
        UUID processInstanceId = start("test-escalation-interrupting.bpmn");

        ProcessInstance pi = queryService.getProcessInstance(processInstanceId);
        assertThat(pi.getCompletedAt()).isNotNull();

        List<ActivityEntity> activities = activities(processInstanceId);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("sub1") && a.getStatus() == ActivityStatus.CANCELLED);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("boundaryEnd") && a.getStatus() == ActivityStatus.COMPLETED);
        // interrupting: the sub-process's normal outgoing (mainEnd) is abandoned
        assertThat(activities).noneMatch(a -> a.getBpmnElementId().equals("mainEnd"));
    }

    @Transactional
    @Test
    void topLevelEscalationThrowEscapesUnhandledAndContinues() throws Exception {
        // a top-level escalation throw with no catching boundary is non-critical: it is ignored and the
        // token continues down its outgoing flow to the end (no incident, instance completes).
        UUID processInstanceId = start("test-escalation-throw.bpmn");

        ProcessInstance pi = queryService.getProcessInstance(processInstanceId);
        assertThat(pi.getCompletedAt()).isNotNull();

        List<ActivityEntity> activities = activities(processInstanceId);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("escThrow") && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("endEvent") && a.getStatus() == ActivityStatus.COMPLETED);
    }
}
