package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.DeploymentDTO;
import com.zorrodev.bpm.contract.dto.DeploymentItemDTO;
import com.zorrodev.bpm.contract.dto.DeploymentResourceType;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.dto.IdDTO;
import com.zorrodev.bpm.engine.service.DeploymentService;
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
public class Process8IntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private DeploymentService deploymentService;

    @Transactional
    @Test
    void testProcess() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test8.bpmn"));
        String dummyBpmn = Files.readString(Paths.get("src/test/files/dummy-process.bpmn"));

        // WO-C8-3b: this fixture binds the call with bindingType="deployment" — parent and child
        // must be laid down in one batch, otherwise the call parks an explicit incident
        // (no silent latest fallback). Same flow as before, new required setup.
        DeploymentDTO batch = deploymentService.deployBatch(
            List.of(bpmnItem(bpmn), bpmnItem(dummyBpmn)), null, null);
        UUID processDefinitionId = batch.getProcesses().stream()
            .filter(p -> "call-activity-process".equals(p.getKey()))
            .findFirst()
            .orElseThrow().getProcessDefinitionId();

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(processDefinitionId);
        IdDTO startResult = runtimeService.startProcessInstance(dto);

        UUID processInstanceId = startResult.getId();

        ProcessInstance processInstance = queryService.getProcessInstance(processInstanceId);
        assertThat(processInstance).isNotNull();
        assertThat(processInstance.getCompletedAt()).isNotNull();
    }

    private static DeploymentItemDTO bpmnItem(String bpmn) {
        DeploymentItemDTO item = new DeploymentItemDTO();
        item.setType(DeploymentResourceType.BPMN);
        item.setContent(bpmn);
        return item;
    }
}
