package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.dto.IdDTO;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
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
 * WO-SEC-59 #1: an exclusive gateway with an unsupported shape (exactly 1 incoming and 1 outgoing,
 * with no default flow) used to be silently "handled" by falling through all branches — the process
 * neither routed nor failed, it just hung (or, in the 2-outgoing no-default case, NPE'd). The fix
 * makes handleExclusive throw a clear IllegalStateException for any non-2-outgoing / no-default shape,
 * which the engine records as an INCIDENT. This test proves the degenerate 1-in/1-out case now raises
 * an incident instead of hanging.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class ExclusiveGatewayUnsupportedShapeIntegrationTests {

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private QueryService queryService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private IncidentRepository incidentRepository;

    @Transactional
    @Test
    void degenerateExclusiveGateway_oneInOneOut_raisesIncidentInsteadOfHang() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-exclusive-gateway-degenerate.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        IdDTO startResult = runtimeService.startProcessInstance(dto);
        UUID processInstanceId = startResult.getId();

        // instance must be parked (never silently completed, never hangs forever)
        ProcessInstance pi = queryService.getProcessInstance(processInstanceId);
        assertThat(pi.getCompletedAt()).isNull();

        ActivityEntity gateway = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .filter(a -> a.getBpmnElementId().equals("xor"))
            .findFirst().orElseThrow();

        List<IncidentEntity> incidents = incidentRepository.findAll().stream()
            .filter(i -> i.getActivityId().equals(gateway.getId()))
            .toList();
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getMessage())
            .contains("unsupported incoming/outgoing shape")
            .doesNotContain("NullPointer");
    }
}
