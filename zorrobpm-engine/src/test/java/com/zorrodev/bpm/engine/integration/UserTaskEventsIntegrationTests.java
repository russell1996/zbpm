package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.TimerStatus;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.entity.UserTaskEventOutboxEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.TimerRepository;
import com.zorrodev.bpm.engine.repository.UserTaskEventOutboxRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import com.zorrodev.bpm.engine.service.TimerJobService;
import com.zorrodev.bpm.engine.test.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * User task lifecycle events written to the outbox, end to end on the engine. Not @Transactional:
 * every command runs in a transaction of its own, as in production. Nothing publishes the outbox here.
 */
@SpringBootTest(classes = TestMain.class, properties = {
    "zorrobpm.events.user-task.enabled=true",
    "zorrobpm.events.user-task.relay-enabled=false",
})
@ActiveProfiles("test")
public class UserTaskEventsIntegrationTests {

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private TimerJobService timerJobService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private UserTaskRepository userTaskRepository;
    @Autowired private UserTaskEventOutboxRepository outboxRepository;
    @Autowired private TimerRepository timerRepository;
    @Autowired private MutableClock clock;
    @Autowired private PlatformTransactionManager transactionManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void cancelLeftoverTimers() {
        inTx(() -> timerRepository.findAll().stream()
            .filter(t -> t.getStatus() == TimerStatus.SCHEDULED)
            .forEach(t -> {
                t.setStatus(TimerStatus.CANCELED);
                timerRepository.save(t);
            }));
    }

    @Test
    void fullLifecycleIsWrittenInOrder() {
        UUID instance = start("user-task-events/approve.bpmn");
        UUID taskId = single(userTasks(instance)).getId();

        inTx(() -> runtimeService.claimUserTask(taskId, "111"));
        inTx(() -> runtimeService.unclaimUserTask(taskId));
        inTx(() -> runtimeService.claimUserTask(taskId, "222"));
        inTx(() -> runtimeService.completeUserTask(taskId, List.of(string("amount", "100"))));

        List<JsonNode> events = events(taskId);
        assertThat(events).extracting(e -> e.get("type").asString())
            .containsExactly("CREATED", "ASSIGNED", "UNASSIGNED", "ASSIGNED", "COMPLETED");
        assertThat(events).extracting(e -> e.get("assignee").isNull() ? null : e.get("assignee").asString())
            .containsExactly(null, "111", null, "222", "222");
        assertThat(events).extracting(e -> e.get("eventId").asString()).doesNotHaveDuplicates();

        JsonNode created = events.get(0);
        assertThat(created.get("bpmnElementId").asString()).isEqualTo("approve");
        assertThat(created.get("name").asString()).isEqualTo("Согласовать");
        assertThat(created.get("formKey").asString()).isEqualTo("ute-approve-form");
        assertThat(created.get("candidateGroups").get(0).asString()).isEqualTo("managers");
        assertThat(created.get("processInstanceId").asString()).isEqualTo(instance.toString());
        assertThat(created.get("processDefinitionKey").asString()).isEqualTo("ute-approve");

        JsonNode completed = events.get(4);
        assertThat(completed.get("completedAt").isNull()).isFalse();
        assertThat(completed.has("amount")).isFalse();
        assertThat(completed.has("variables")).isFalse();
        assertThat(events.get(1).get("completedAt").isNull()).isTrue();
    }

    @Test
    void assigneeFromTheModelComesWithTheCreation() {
        UUID instance = start("user-task-events/assigned.bpmn");
        UUID taskId = single(userTasks(instance)).getId();

        List<JsonNode> events = events(taskId);
        assertThat(events).extracting(e -> e.get("type").asString()).containsExactly("CREATED");
        assertThat(events.get(0).get("assignee").asString()).isEqualTo("111");
    }

    @Test
    void rejectedClaimWritesNothing() {
        UUID instance = start("user-task-events/assigned.bpmn");
        UUID taskId = single(userTasks(instance)).getId();

        assertThatThrownBy(() -> inTx(() -> runtimeService.claimUserTask(taskId, "222"))).isInstanceOf(RuntimeException.class);

        assertThat(events(taskId)).extracting(e -> e.get("type").asString()).containsExactly("CREATED");
    }

    @Test
    void interruptingTimerCancelsTheTask() {
        UUID instance = start("boundary/interrupting-user-task.bpmn");
        UUID taskId = single(userTasks(instance)).getId();

        clock.advance(Duration.ofHours(1));
        fireAll();

        List<JsonNode> events = events(taskId);
        assertThat(events).extracting(e -> e.get("type").asString()).containsExactly("CREATED", "CANCELED");
        assertThat(events.get(1).get("canceledAt").isNull()).isFalse();
        assertThat(events.get(1).get("completedAt").isNull()).isTrue();
    }

    @Test
    void multiInstanceEndingEarlyCancelsTheOtherTasks() {
        UUID instance = start("user-task-events/multi.bpmn", json("items", "[1,2,3]"));
        List<UserTaskEntity> tasks = userTasks(instance).stream()
            .sorted(Comparator.comparing(UserTaskEntity::getLoopIndex)).toList();
        assertThat(tasks).hasSize(3);

        inTx(() -> runtimeService.completeUserTask(tasks.get(0).getId(), List.of()));

        assertThat(events(tasks.get(0).getId())).extracting(e -> e.get("type").asString()).containsExactly("CREATED", "COMPLETED");
        for (UserTaskEntity task : tasks.subList(1, 3)) {
            assertThat(events(task.getId())).extracting(e -> e.get("type").asString()).containsExactly("CREATED", "CANCELED");
        }
        JsonNode created = events(tasks.get(2).getId()).get(0);
        assertThat(created.get("loopIndex").asInt()).isEqualTo(2);
        assertThat(created.get("loopTotal").asInt()).isEqualTo(3);
    }

    @Test
    void completionRejectedByTheOutputMappingWritesNothing() {
        UUID instance = start("output-mapping/user-task-failing.bpmn");
        UUID taskId = single(userTasks(instance)).getId();

        assertThatThrownBy(() -> inTx(() -> runtimeService.completeUserTask(taskId, List.of()))).isInstanceOf(RuntimeException.class);

        assertThat(events(taskId)).extracting(e -> e.get("type").asString()).containsExactly("CREATED");
        assertThat(userTaskRepository.findById(taskId).orElseThrow().getCompletedAt()).isNull();
    }

    @Test
    void rolledBackCommandLeavesNoEvent() {
        Set<UUID> before = outboxRepository.findAll().stream().map(UserTaskEventOutboxEntity::getEventId).collect(Collectors.toSet());

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            startInCurrentTransaction("user-task-events/approve.bpmn");
            status.setRollbackOnly();
        });

        assertThat(outboxRepository.findAll()).extracting(UserTaskEventOutboxEntity::getEventId)
            .containsExactlyInAnyOrderElementsOf(before);
    }

    // ---------------------------------------------------------------- helpers

    private List<JsonNode> events(UUID taskId) {
        return outboxRepository.findAll().stream()
            .filter(row -> taskId.equals(row.getUserTaskId()))
            .sorted(Comparator.comparing(UserTaskEventOutboxEntity::getSeq))
            .map(row -> objectMapper.readTree(row.getPayload()))
            .toList();
    }

    private List<UserTaskEntity> userTasks(UUID processInstanceId) {
        return userTaskRepository.findByProcessInstanceId(processInstanceId);
    }

    private void fireAll() {
        for (int round = 0; round < 20 && timerJobService.fireDueTimers() > 0; round++) {
            // fire until nothing is due
        }
    }

    private UUID start(String file, ProcessVariable... variables) {
        return inTx(() -> startInCurrentTransaction(file, variables));
    }

    private UUID startInCurrentTransaction(String file, ProcessVariable... variables) {
        try {
            UUID definitionId = processDefinitionService.addProcessDefinition(Files.readString(Paths.get("src/test/files/" + file))).getId();
            StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
            dto.setProcessDefinitionId(definitionId);
            dto.setVariables(List.of(variables));
            return runtimeService.startProcessInstance(dto).getId();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T inTx(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }

    private void inTx(Runnable action) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> action.run());
    }

    private static <T> T single(List<T> items) {
        assertThat(items).hasSize(1);
        return items.get(0);
    }

    private static ProcessVariable string(String name, String value) {
        return variable(name, ProcessVariableType.STRING, value);
    }

    private static ProcessVariable json(String name, String value) {
        return variable(name, ProcessVariableType.JSON, value);
    }

    private static ProcessVariable variable(String name, ProcessVariableType type, String value) {
        ProcessVariable variable = new ProcessVariable();
        variable.setName(name);
        variable.setType(type);
        variable.setValue(value);
        return variable;
    }
}
