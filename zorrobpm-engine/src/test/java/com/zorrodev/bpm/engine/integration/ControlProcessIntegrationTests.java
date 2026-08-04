package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.TimerJobEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.TimerJobRepository;
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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the user's original controlProcess BPMN schema.
 * Validates that the engine correctly handles:
 * - Parallel gateway fork (3 branches: MI userTask, notification, work task)
 * - MI aggregation before gwDecision
 * - CLOSE action → srvParentClose → process completion (or re-entry)
 * - ADD_EXECUTORS action → loop back to srvCreateTask
 * - Non-interrupting boundary timer on MI userTask
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class ControlProcessIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private TimerJobRepository timerJobRepository;

    /**
     * Сценарий CLOSE без возврата:
     * start → srvCreateTask → gwCreateTask (fork: utExecute + srvNotification + srvWorkTask)
     * → complete srvNotification, srvWorkTask → complete both MI utExecute with CLOSE
     * → srvParentClose → complete → gwParent (default flow) → endControlProcess
     */
    @Transactional
    @Test
    void fullCloseWithoutReentry() throws Exception {
        deployAndStart(false);

        UUID piId = deployAndStart(false);

        // После gwCreateTask fork, должны быть: srvNotification, srvWorkTask, utExecute (2 MI)
        // Сначала завершаем сервисные задачи (они не блокируют главный поток)
        completeServiceTask(piId, "srvNotification");
        completeServiceTask(piId, "srvWorkTask");

        // Завершаем оба MI utExecute с action=CLOSE
        List<ActivityEntity> miInstances = findActivities(piId, "utExecute", ActivityStatus.CREATED);
        assertThat(miInstances).as("Должно быть 2 MI экземпляра utExecute").hasSize(2);

        for (ActivityEntity mi : miInstances) {
            runtimeService.completeUserTask(mi.getId(), List.of(
                variable("action", ProcessVariableType.STRING, "CLOSE")
            ));
        }

        // После агрегации всех MI → gwDecision (action=CLOSE) → srvParentClose
        ActivityEntity parentClose = findActivity(piId, "srvParentClose", ActivityStatus.CREATED);
        assertThat(parentClose).as("srvParentClose должен быть создан после CLOSE").isNotNull();

        // Завершаем srvParentClose — gwParent (default → endControlProcess)
        runtimeService.completeServiceTask(parentClose.getId(), List.of(
            variable("needReturn", ProcessVariableType.BOOLEAN, "false")
        ));

        // Проверяем что процесс завершён
        ProcessInstance pi = queryService.getProcessInstance(piId);
        assertThat(pi.getCompletedAt()).as("Процесс должен быть завершён").isNotNull();
    }

    /**
     * Сценарий CLOSE с возвратом (re-entry):
     * start → ... → srvParentClose → gwParent (needReturn=true) → utExecute (НОВЫЙ батч)
     */
    @Transactional
    @Test
    void closeWithReentry() throws Exception {
        UUID piId = deployAndStart(true);

        // Завершаем сервисные задачи параллельных веток
        completeServiceTask(piId, "srvNotification");
        completeServiceTask(piId, "srvWorkTask");

        // Завершаем оба MI utExecute с action=CLOSE
        List<ActivityEntity> miInstances = findActivities(piId, "utExecute", ActivityStatus.CREATED);
        assertThat(miInstances).as("Должно быть 2 MI экземпляра utExecute").hasSize(2);

        for (ActivityEntity mi : miInstances) {
            runtimeService.completeUserTask(mi.getId(), List.of(
                variable("action", ProcessVariableType.STRING, "CLOSE")
            ));
        }

        // srvParentClose создан
        ActivityEntity parentClose = findActivity(piId, "srvParentClose", ActivityStatus.CREATED);
        assertThat(parentClose).as("srvParentClose должен быть создан").isNotNull();

        // Завершаем srvParentClose с needReturn=true → re-entry в utExecute
        runtimeService.completeServiceTask(parentClose.getId(), List.of(
            variable("needReturn", ProcessVariableType.BOOLEAN, "true")
        ));

        // Проверяем re-entry: новый батч MI utExecute создан
        List<ActivityEntity> newMiInstances = findActivities(piId, "utExecute", ActivityStatus.CREATED);
        assertThat(newMiInstances).as("Re-entry должен создать новый батч MI utExecute").isNotEmpty();
    }

    /**
     * Сценарий ADD_EXECUTORS: MI → gwDecision → loop → srvCreateTask
     */
    @Transactional
    @Test
    void addExecutorsLoop() throws Exception {
        // 1 исполнитель для простоты
        ProcessVariable executors = variable("currentExecutors", ProcessVariableType.JSON, "[\"user1\"]");
        ProcessVariable dueDate = variable("dueDate", ProcessVariableType.STRING, "2099-01-01T00:00:00Z");
        ProcessVariable needReturn = variable("needReturn", ProcessVariableType.BOOLEAN, "false");

        String bpmn = Files.readString(Paths.get("src/test/files/controlProcess.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of(executors, dueDate, needReturn));
        UUID piId = runtimeService.startProcessInstance(dto).getId();

        // Завершаем srvCreateTask
        completeServiceTask(piId, "srvCreateTask");

        // Завершаем параллельные сервисные задачи
        completeServiceTask(piId, "srvNotification");
        completeServiceTask(piId, "srvWorkTask");

        // Завершаем единственный MI utExecute с action=ADD_EXECUTORS
        List<ActivityEntity> miInstances = findActivities(piId, "utExecute", ActivityStatus.CREATED);
        assertThat(miInstances).as("Должен быть 1 MI экземпляр").hasSize(1);

        runtimeService.completeUserTask(miInstances.get(0).getId(), List.of(
            variable("action", ProcessVariableType.STRING, "ADD_EXECUTORS")
        ));

        // Проверяем loop: srvCreateTask создан снова
        ActivityEntity srvCreateAgain = findActivity(piId, "srvCreateTask", ActivityStatus.CREATED);
        assertThat(srvCreateAgain).as("ADD_EXECUTORS должен вернуть к srvCreateTask").isNotNull();
    }

    /**
     * Проверка неинтераптивного граничного таймера на MI userTask
     */
    @Transactional
    @Test
    void nonInterruptingBoundaryTimerOnMI() throws Exception {
        ProcessVariable executors = variable("currentExecutors", ProcessVariableType.JSON, "[\"user1\",\"user2\"]");
        ProcessVariable dueDate = variable("dueDate", ProcessVariableType.STRING, "2027-01-01T00:00:00Z");
        ProcessVariable needReturn = variable("needReturn", ProcessVariableType.BOOLEAN, "false");

        String bpmn = Files.readString(Paths.get("src/test/files/controlProcess.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of(executors, dueDate, needReturn));
        UUID piId = runtimeService.startProcessInstance(dto).getId();

        // Завершаем srvCreateTask
        completeServiceTask(piId, "srvCreateTask");

        // Проверяем что таймеры созданы на каждый MI экземпляр
        List<TimerJobEntity> timerJobs = timerJobRepository.findAll().stream()
            .filter(j -> "tmrCheck".equals(j.getBoundaryElementId()))
            .toList();
        assertThat(timerJobs).as("Должно быть 2 timer job-а на 2 MI экземпляра").hasSize(2);
    }

    // ========== HELPER METHODS ==========

    private UUID deployAndStart(boolean needReturnValue) throws Exception {
        ProcessVariable executors = variable("currentExecutors", ProcessVariableType.JSON, "[\"user1\",\"user2\"]");
        ProcessVariable dueDate = variable("dueDate", ProcessVariableType.STRING, "2099-01-01T00:00:00Z");
        ProcessVariable needReturn = variable("needReturn", ProcessVariableType.BOOLEAN, String.valueOf(needReturnValue));

        String bpmn = Files.readString(Paths.get("src/test/files/controlProcess.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of(executors, dueDate, needReturn));
        UUID piId = runtimeService.startProcessInstance(dto).getId();

        // Завершаем srvCreateTask (первый service task после start)
        completeServiceTask(piId, "srvCreateTask");

        return piId;
    }

    private void completeServiceTask(UUID piId, String elementId) {
        ActivityEntity task = findActivity(piId, elementId, ActivityStatus.CREATED);
        if (task != null) {
            runtimeService.completeServiceTask(task.getId(), List.of());
        }
    }

    private ActivityEntity findActivity(UUID piId, String elementId, ActivityStatus status) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(piId))
            .filter(a -> a.getBpmnElementId().equals(elementId) && a.getStatus() == status)
            .findFirst().orElse(null);
    }

    private List<ActivityEntity> findActivities(UUID piId, String elementId, ActivityStatus status) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(piId))
            .filter(a -> a.getBpmnElementId().equals(elementId) && a.getStatus() == status)
            .collect(Collectors.toList());
    }

    private static ProcessVariable variable(String name, ProcessVariableType type, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(type);
        v.setValue(value);
        return v;
    }
}
