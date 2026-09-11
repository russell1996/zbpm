package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.DomainEventEntity;
import com.zorrodev.bpm.engine.repository.DomainEventRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-EVT-1: Integration tests for domain event emission.
 * Verifies that each DBService operation emits the correct event type with correct fields.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
class DomainEventEmissionIntegrationTests {

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private DomainEventRepository domainEventRepository;
    @Autowired private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    /** WO-AUDIT-3 (P4): per-instance reads are bounded — test instances emit a handful. */
    private static final int INSTANCE_EVENT_CAP = 100;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        domainEventRepository.deleteAllInBatch();
    }

    private ProcessVariable pv(String name, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setValue(value);
        v.setType(ProcessVariableType.STRING);
        return v;
    }

    private UUID deploy(String bpmnFile) {
        return tx.execute(s -> {
            try {
                String bpmn = Files.readString(Paths.get("src/test/files/" + bpmnFile));
                return processDefinitionService.addProcessDefinition(bpmn).getId();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private UUID startProcess(String bpmnFile) {
        return tx.execute(s -> {
            try {
                String bpmn = Files.readString(Paths.get("src/test/files/" + bpmnFile));
                ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
                StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
                dto.setProcessDefinitionId(model.getId());
                return runtimeService.startProcessInstance(dto).getId();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void processInstanceStarted_eventEmitted() {
        UUID pdId = deploy("test1.bpmn");
        long beforeCount = domainEventRepository.count();

        UUID piId = startProcess("test1.bpmn");

        List<DomainEventEntity> events = domainEventRepository.findByProcessInstanceId(piId, INSTANCE_EVENT_CAP);
        assertThat(events).isNotEmpty();

        DomainEventEntity started = events.stream()
            .filter(e -> "process-instance.started".equals(e.getType()))
            .findFirst()
            .orElseThrow();

        assertThat(started.getId()).isNotNull();
        assertThat(started.getVersion()).isEqualTo(1);
        assertThat(started.getOccurredAt()).isBeforeOrEqualTo(Instant.now());
        assertThat(started.getProcessDefinitionId()).isEqualTo(pdId);
        assertThat(started.getProcessInstanceId()).isEqualTo(piId);
        assertThat(started.getOwnerScope()).isEqualTo(pdId.toString());
    }

    @Test
    void processInstanceCompleted_eventEmitted() {
        UUID piId = startProcess("test1.bpmn");

        List<DomainEventEntity> events = domainEventRepository.findByProcessInstanceId(piId, INSTANCE_EVENT_CAP);
        assertThat(events).isNotEmpty();

        DomainEventEntity completed = events.stream()
            .filter(e -> "process-instance.completed".equals(e.getType()))
            .findFirst()
            .orElse(null);

        // test1.bpmn is start→end, so it should complete immediately
        assertThat(completed).isNotNull();
        assertThat(completed.getProcessInstanceId()).isEqualTo(piId);
    }

    @Test
    void activityCompleted_eventEmitted() {
        UUID piId = startProcess("test1.bpmn");

        List<DomainEventEntity> events = domainEventRepository.findByProcessInstanceId(piId, INSTANCE_EVENT_CAP);

        DomainEventEntity activityCompleted = events.stream()
            .filter(e -> "activity.completed".equals(e.getType()))
            .findFirst()
            .orElse(null);

        // Should have at least one activity.completed event (the end event or flow)
        assertThat(activityCompleted).isNotNull();
        assertThat(activityCompleted.getElementId()).isNotBlank();
    }

    @Test
    void userTaskCreated_eventEmitted() {
        UUID piId = startProcess("test4.bpmn");

        List<DomainEventEntity> events = domainEventRepository.findByProcessInstanceId(piId, INSTANCE_EVENT_CAP);

        DomainEventEntity userTaskCreated = events.stream()
            .filter(e -> "user-task.created".equals(e.getType()))
            .findFirst()
            .orElse(null);

        assertThat(userTaskCreated).isNotNull();
        assertThat(userTaskCreated.getElementId()).isNotBlank();
        assertThat(userTaskCreated.getData()).containsKey("activityId");
    }

    @Test
    void serviceTaskCreated_eventEmitted() {
        UUID piId = startProcess("test3.bpmn");

        List<DomainEventEntity> events = domainEventRepository.findByProcessInstanceId(piId, INSTANCE_EVENT_CAP);

        DomainEventEntity serviceTaskCreated = events.stream()
            .filter(e -> "service-task.created".equals(e.getType()))
            .findFirst()
            .orElse(null);

        assertThat(serviceTaskCreated).isNotNull();
        assertThat(serviceTaskCreated.getElementId()).isNotBlank();
        assertThat(serviceTaskCreated.getData()).containsKey("activityId");
    }

    @Test
    void allEventsHaveMonotonicallyIncreasingSequence() {
        UUID piId = startProcess("test4.bpmn");

        List<DomainEventEntity> events = domainEventRepository.findByProcessInstanceId(piId, INSTANCE_EVENT_CAP);
        assertThat(events).hasSizeGreaterThan(1);

        long prevSequence = 0;
        for (DomainEventEntity event : events) {
            assertThat(event.getSequence()).isGreaterThan(prevSequence);
            prevSequence = event.getSequence();
        }
    }

    @Test
    void ownerScope_matchesProcessDefinitionId() {
        UUID pdId = deploy("test1.bpmn");
        UUID piId = startProcess("test1.bpmn");

        List<DomainEventEntity> events = domainEventRepository.findByProcessInstanceId(piId, INSTANCE_EVENT_CAP);
        for (DomainEventEntity event : events) {
            assertThat(event.getOwnerScope()).isEqualTo(pdId.toString());
        }
    }
}
