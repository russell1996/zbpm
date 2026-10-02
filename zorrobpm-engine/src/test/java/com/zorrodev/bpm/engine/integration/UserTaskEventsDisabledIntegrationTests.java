package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.repository.UserTaskEventOutboxRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Without {@code zorrobpm.events.user-task.enabled} no user task event is written. */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class UserTaskEventsDisabledIntegrationTests {

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private UserTaskRepository userTaskRepository;
    @Autowired private UserTaskEventOutboxRepository outboxRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void lifecycleWritesNoEvent() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        UUID instance = tx.execute(status -> {
            try {
                UUID definitionId = processDefinitionService.addProcessDefinition(
                    Files.readString(Paths.get("src/test/files/user-task-events/approve.bpmn"))).getId();
                StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
                dto.setProcessDefinitionId(definitionId);
                return runtimeService.startProcessInstance(dto).getId();
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
        UUID taskId = userTaskRepository.findByProcessInstanceId(instance).get(0).getId();

        tx.executeWithoutResult(status -> runtimeService.claimUserTask(taskId, "111"));
        tx.executeWithoutResult(status -> runtimeService.completeUserTask(taskId, List.of()));

        assertThat(outboxRepository.findAll()).noneMatch(row -> taskId.equals(row.getUserTaskId()));
    }
}
