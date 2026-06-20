package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
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
public class TransactionCancelIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private VariableRepository variableRepository;

    @Transactional
    @Test
    void cancelEndCompensatesTransactionAndContinuesFromCancelBoundary() throws Exception {
        // tx runs txTask (log += 1), then a cancel end fires: the transaction is compensated (txHandler
        // log += 100), the scope is cancelled and flow continues from the cancel boundary (afterCancel
        // log += 1000). Final log = 1101 proves all three ran; the transaction's normal end is never taken.
        String bpmn = Files.readString(Paths.get("src/test/files/test-transaction-cancel.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        ProcessVariable log = new ProcessVariable();
        log.setName("log");
        log.setType(ProcessVariableType.LONG);
        log.setValue("0");

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of(log));
        UUID processInstanceId = runtimeService.startProcessInstance(dto).getId();

        ProcessInstance pi = queryService.getProcessInstance(processInstanceId);
        assertThat(pi.getCompletedAt()).isNotNull();

        ProcessVariableEntity result = variableRepository.findByNameAndProcessInstanceId("log", processInstanceId).orElseThrow();
        assertThat(result.getTextValue()).isEqualTo("1101");

        List<ActivityEntity> activities = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .toList();
        // the transaction container was cancelled; the handler and the cancel-boundary path ran; normal end did not
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("tx") && a.getStatus() == ActivityStatus.CANCELLED);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("txHandler") && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("endCancel") && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(activities).noneMatch(a -> a.getBpmnElementId().equals("normalEnd"));
    }
}
