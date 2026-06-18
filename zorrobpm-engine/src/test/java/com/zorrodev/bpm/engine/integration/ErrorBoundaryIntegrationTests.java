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
public class ErrorBoundaryIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityRepository activityRepository;

    @Transactional
    @Test
    void errorEndInSubprocessIsCaughtByErrorBoundary() throws Exception {
        // start -> sub1 { subStart -> subErrEnd (throws E-1) } with an error boundary on sub1
        // routing to boundaryEnd; the normal endEvent must NOT be reached.
        String bpmn = Files.readString(Paths.get("src/test/files/test-error-boundary.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        IdDTO startResult = runtimeService.startProcessInstance(dto);

        UUID processInstanceId = startResult.getId();
        ProcessInstance processInstance = queryService.getProcessInstance(processInstanceId);
        assertThat(processInstance.getCompletedAt()).isNotNull();

        List<ActivityEntity> activities = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .toList();

        // the error end fired, the subprocess was cancelled, and flow continued via the boundary
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("subErrEnd"));
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("sub1") && a.getStatus() == ActivityStatus.CANCELLED);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("boundaryEnd") && a.getStatus() == ActivityStatus.COMPLETED);
        // the normal (non-boundary) end must never have been reached
        assertThat(activities).noneMatch(a -> a.getBpmnElementId().equals("endEvent"));
    }
}
