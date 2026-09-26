package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.exception.TaskCompletionInProgressException;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-DEBT-6 Срез 3: characterization tests for completeUserTask.
 * Slice-0 tests do not cover the user-task path, so this class pins it
 * on the UNTOUCHED method BEFORE the Slice-3 extraction: happy path,
 * guard-status ignore, and the three listener-guard 409s
 * (assigning/updating/completing).
 * <p>
 * Прод-код НЕ трогается — только новый интеграционный тест.
 */
@Tag("pg")
public class CompleteUserTaskCharacterizationPgIT extends PostgresIT {

    @Autowired ProcessDefinitionService processDefinitionService;
    @Autowired RuntimeService runtimeService;
    @Autowired QueryService queryService;
    @Autowired ActivityRepository activityRepository;
    @Autowired DBService dbService;
    @Autowired PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
    }

    // ----------------------------------------------------------------
    // helpers
    // ----------------------------------------------------------------

    /** Deploys a BPMN model file and starts a process instance. */
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

    /** Finds the parked (uncompleted) user task of an instance. */
    private UUID parkedUserTaskId(UUID pi) {
        return tx.execute(s -> {
            UserTaskQuery query = new UserTaskQuery();
            query.setProcessInstanceId(pi);
            PagedDataDTO<UserTask> tasks = queryService.findUserTasks(query, null);
            return tasks.getData().stream()
                    .filter(t -> t.getCompletedAt() == null)
                    .findFirst().orElseThrow().getId();
        });
    }

    private boolean isDone(UUID pi) {
        return tx.execute(s -> queryService.getProcessInstance(pi).getCompletedAt() != null);
    }

    private long countByElement(UUID pi, String elementId, ActivityStatus status) {
        return tx.execute(s -> activityRepository.findAll().stream()
                .filter(a -> a.getProcessInstanceId().equals(pi)
                        && a.getBpmnElementId().equals(elementId)
                        && a.getStatus() == status)
                .count());
    }

    private long countEndEvents(UUID pi) {
        return tx.execute(s -> activityRepository.findAll().stream()
                .filter(a -> a.getProcessInstanceId().equals(pi)
                        && a.getBpmnElementId().equals("endEvent"))
                .count());
    }

    private ProcessVariable strVar(String name, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(ProcessVariableType.STRING);
        v.setValue(value);
        return v;
    }

    // ----------------------------------------------------------------
    // tests
    // ----------------------------------------------------------------

    /**
     * Happy path: plain user task (no listeners) completes normally —
     * activity COMPLETED, instance done, exactly one endEvent row.
     */
    @Test
    void completeUserTask_plainTask_completesNormally() {
        UUID pi = startProcess("test-usertask-query.bpmn");

        assertThat(countByElement(pi, "approve", ActivityStatus.CREATED))
                .as("approve should be CREATED (parked) before completion")
                .isEqualTo(1);

        UUID userTaskId = parkedUserTaskId(pi);
        assertThat(userTaskId).as("parked user task must exist").isNotNull();

        tx.executeWithoutResult(s -> runtimeService.completeUserTask(userTaskId, List.of()));

        assertThat(countByElement(pi, "approve", ActivityStatus.COMPLETED))
                .as("approve should be COMPLETED after completion")
                .isEqualTo(1);
        assertThat(countEndEvents(pi))
                .as("exactly one endEvent row after single completion")
                .isEqualTo(1);
        assertThat(isDone(pi))
                .as("process instance should be completed")
                .isTrue();
    }

    /**
     * Guard-status: completing an already-COMPLETED user task is ignored —
     * no double-advance (still exactly one endEvent row).
     */
    @Test
    void completeUserTask_alreadyCompleted_ignored() {
        UUID pi = startProcess("test-usertask-query.bpmn");

        UUID userTaskId = parkedUserTaskId(pi);
        assertThat(userTaskId).isNotNull();

        tx.executeWithoutResult(s -> runtimeService.completeUserTask(userTaskId, List.of()));
        assertThat(countByElement(pi, "approve", ActivityStatus.COMPLETED))
                .as("approve should be COMPLETED after first completion")
                .isEqualTo(1);
        assertThat(countEndEvents(pi)).isEqualTo(1);

        // Complete again — guard must swallow the redelivery.
        tx.executeWithoutResult(s -> runtimeService.completeUserTask(userTaskId, List.of()));

        assertThat(countByElement(pi, "approve", ActivityStatus.COMPLETED))
                .as("still exactly 1 COMPLETED (no double-advance)")
                .isEqualTo(1);
        assertThat(countEndEvents(pi))
                .as("guard must prevent double-advance: still exactly 1 endEvent row")
                .isEqualTo(1);
        assertThat(isDone(pi)).isTrue();
    }

    /**
     * Assigning guard: completing while an assigning phase is open throws
     * TaskCompletionInProgressException (REST 409), task stays parked.
     */
    @Test
    void completeUserTask_assigningPhaseOpen_throws409() {
        UUID pi = startProcess("test-c8-task-listeners-assigning.bpmn");

        UUID userTaskId = parkedUserTaskId(pi);
        assertThat(userTaskId).isNotNull();
        assertThat(tx.execute(s -> dbService.getPendingAssigningListenerIndex(userTaskId)).intValue())
                .as("assigning phase should be open after start")
                .isEqualTo(0);

        assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                runtimeService.completeUserTask(userTaskId, List.of())))
                .as("complete during open assigning phase must conflict")
                .isInstanceOf(TaskCompletionInProgressException.class);

        assertThat(countByElement(pi, "review", ActivityStatus.COMPLETED))
                .as("task must stay parked, not completed")
                .isEqualTo(0);
        assertThat(isDone(pi)).isFalse();
    }

    /**
     * Updating guard: completing WITH variables opens the updating phase
     * (task parked, phase index 0); a repeat complete while open throws 409.
     */
    @Test
    void completeUserTask_withVariables_opensUpdatingPhase() {
        UUID pi = startProcess("test-c8-task-listeners-updating.bpmn");

        UUID userTaskId = parkedUserTaskId(pi);
        assertThat(userTaskId).isNotNull();

        tx.executeWithoutResult(s -> runtimeService.completeUserTask(
                userTaskId, List.of(strVar("note", "hello"))));

        assertThat(countByElement(pi, "review", ActivityStatus.COMPLETED))
                .as("task must stay parked while updating phase runs")
                .isEqualTo(0);
        assertThat(tx.execute(s -> dbService.getPendingUpdatingListenerIndex(userTaskId)).intValue())
                .as("updating phase should be open at index 0")
                .isEqualTo(0);

        assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                runtimeService.completeUserTask(userTaskId, List.of(strVar("note", "again")))))
                .as("repeat complete WITH variables during open updating phase must conflict")
                .isInstanceOf(TaskCompletionInProgressException.class);
    }

    /**
     * Completing guard: completing an element with completing listeners opens
     * the phase (task parked, phase index 0); a repeat complete throws 409.
     */
    @Test
    void completeUserTask_completingListeners_opensCompletingPhase() {
        UUID pi = startProcess("test-c8-task-listeners-completing.bpmn");

        UUID userTaskId = parkedUserTaskId(pi);
        assertThat(userTaskId).isNotNull();

        tx.executeWithoutResult(s -> runtimeService.completeUserTask(userTaskId, List.of()));

        assertThat(countByElement(pi, "review", ActivityStatus.COMPLETED))
                .as("task must stay parked while completing phase runs")
                .isEqualTo(0);
        assertThat(tx.execute(s -> dbService.getPendingCompletingListenerIndex(userTaskId)).intValue())
                .as("completing phase should be open at index 0")
                .isEqualTo(0);

        assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                runtimeService.completeUserTask(userTaskId, List.of())))
                .as("repeat complete during open completing phase must conflict")
                .isInstanceOf(TaskCompletionInProgressException.class);
    }
}
