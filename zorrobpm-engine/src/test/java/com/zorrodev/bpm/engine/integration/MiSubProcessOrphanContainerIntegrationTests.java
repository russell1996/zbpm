package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.TimerJobRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import com.zorrodev.bpm.engine.service.DBService;
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
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-43 — контейнер раньше закончившейся ветки параллельного MI-подпроцесса
 * остаётся CREATED навсегда (orphan): виден как «активный» в диагностике/UI,
 * {@code activeTokenCount} врёт, на нём могут жить boundary-расписания.
 *
 * <p>RED-база: {@code FlowNavigator.finishMiSubProcessInstance} при
 * {@code arrived < expected} выходит раньше, чем вызывающий completes контейнер, —
 * контейнер completes только у последней пришедшей ветки.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class MiSubProcessOrphanContainerIntegrationTests {

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
    @Autowired
    private IncidentRepository incidentRepository;
    @Autowired
    private TimerJobRepository timerJobRepository;
    @Autowired
    private DBService dbService;
    @Autowired
    private com.zorrodev.bpm.engine.service.ActivityService activityService;

    private UUID deploy(String bpmnFile) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + bpmnFile));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        return model.getId();
    }

    private UUID start(UUID pdId) {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        dto.setVariables(List.of());
        return runtimeService.startProcessInstance(dto).getId();
    }

    private List<ActivityEntity> activities(UUID pi, String bpmnElementId, ActivityStatus status) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getBpmnElementId().equals(bpmnElementId) && a.getStatus() == status)
            .toList();
    }

    private long liveActivities(UUID pi) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED
                || a.getStatus() == ActivityStatus.IN_PROGRESS)
            .count();
    }

    private long incidents(UUID pi) {
        var activityIds = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .map(ActivityEntity::getId)
            .collect(Collectors.toSet());
        return incidentRepository.findAll().stream()
            .filter(i -> activityIds.contains(i.getActivityId()))
            .count();
    }

    /** Слот (loopCounter) контейнера-инстанса, которому принадлежит внутренняя задача. */
    private int containerSlotOf(UUID pi, UUID innerTaskActivityId) {
        com.zorrodev.bpm.engine.dto.Activity live = dbService.getActivity(innerTaskActivityId);
        com.zorrodev.bpm.engine.dto.Token token = dbService.findToken(live.getToken()).orElseThrow();
        UUID containerId = token.getScopeActivityId();
        assertThat(containerId).as("inner task must run on a scope token").isNotNull();
        return variableRepository.findByProcessInstanceIdAndScopeId(pi, containerId).stream()
            .filter(v -> "loopCounter".equals(v.getName()))
            .findFirst()
            .map(v -> Integer.parseInt(v.getTextValue().trim()))
            .orElseThrow(() -> new AssertionError("No scoped loopCounter on container " + containerId));
    }

    private UUID containerOf(UUID innerTaskActivityId) {
        com.zorrodev.bpm.engine.dto.Activity live = dbService.getActivity(innerTaskActivityId);
        com.zorrodev.bpm.engine.dto.Token token = dbService.findToken(live.getToken()).orElseThrow();
        assertThat(token.getScopeActivityId()).isNotNull();
        return token.getScopeActivityId();
    }

    private ActivityStatus containerStatus(UUID containerId) {
        return activityRepository.findById(containerId).orElseThrow().getStatus();
    }

    @Transactional
    @Test
    void firstFinishedBranchContainerIsCompleted_twoInstances() throws Exception {
        UUID pdId = deploy("test-c843-orphan-two-service.bpmn");
        UUID pi = start(pdId);
        List<ActivityEntity> w = activities(pi, "w", ActivityStatus.CREATED);
        assertThat(w).hasSize(2);

        UUID first = w.get(0).getId();
        UUID firstContainer = containerOf(first);
        runtimeService.completeServiceTask(first, List.of());

        assertThat(containerStatus(firstContainer))
            .as("container of the first finished branch completes with its branch, not with the last one")
            .isEqualTo(ActivityStatus.COMPLETED);
        // Соседняя ветка жива: join ещё не собран, dump не создан.
        assertThat(activities(pi, "w", ActivityStatus.CREATED)).hasSize(1);
        assertThat(activities(pi, "dump", ActivityStatus.CREATED)).isEmpty();

        runtimeService.completeServiceTask(w.get(1).getId(), List.of());
        List<ActivityEntity> dump = activities(pi, "dump", ActivityStatus.CREATED);
        assertThat(dump).as("join proceeds after the last branch").hasSize(1);
        runtimeService.completeServiceTask(dump.get(0).getId(), List.of());

        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertThat(liveActivities(pi)).as("no live activities left after process end").isZero();
        assertThat(incidents(pi)).isZero();
    }

    @Transactional
    @Test
    void outOfOrderCompletion_threeInstances() throws Exception {
        UUID pdId = deploy("test-c843-orphan-three-service.bpmn");
        UUID pi = start(pdId);
        List<ActivityEntity> w = activities(pi, "w", ActivityStatus.CREATED);
        assertThat(w).hasSize(3);
        // Завершаем НЕ по индексу: сначала слот 3, потом слот 1, последним — слот 2.
        w = w.stream().sorted(Comparator.comparingInt(a -> containerSlotOf(pi, a.getId()))).toList();
        ActivityEntity slot1 = w.get(0);
        ActivityEntity slot2 = w.get(1);
        ActivityEntity slot3 = w.get(2);
        assertThat(containerSlotOf(pi, slot1.getId())).isEqualTo(1);
        assertThat(containerSlotOf(pi, slot3.getId())).isEqualTo(3);

        runtimeService.completeServiceTask(slot3.getId(), List.of());
        assertThat(containerStatus(containerOf(slot3.getId())))
            .as("non-last finished branch (slot 3) completes its own container")
            .isEqualTo(ActivityStatus.COMPLETED);
        assertThat(activities(pi, "dump", ActivityStatus.CREATED))
            .as("join waits for the remaining branches").isEmpty();

        runtimeService.completeServiceTask(slot1.getId(), List.of());
        assertThat(containerStatus(containerOf(slot1.getId())))
            .as("second finished branch (slot 1) completes its own container")
            .isEqualTo(ActivityStatus.COMPLETED);
        assertThat(activities(pi, "dump", ActivityStatus.CREATED)).isEmpty();

        runtimeService.completeServiceTask(slot2.getId(), List.of());
        List<ActivityEntity> dump = activities(pi, "dump", ActivityStatus.CREATED);
        assertThat(dump).as("join proceeds exactly once after the last branch").hasSize(1);
        runtimeService.completeServiceTask(dump.get(0).getId(), List.of());

        assertThat(activities(pi, "miSub", ActivityStatus.COMPLETED)).hasSize(3);
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertThat(liveActivities(pi)).as("no live activities left after process end").isZero();
        assertThat(incidents(pi)).isZero();
    }

    @Transactional
    @Test
    void outOfOrderCompletion_userTasksInside() throws Exception {
        // Форма консьюмера: user task внутри MI-подпроцесса (существующая фикстура).
        UUID pdId = deploy("test-c840-mi-sub-minimal.bpmn");
        UUID pi = start(pdId);
        List<ActivityEntity> tasks = activities(pi, "utProbe", ActivityStatus.CREATED);
        assertThat(tasks).hasSize(3);
        tasks = tasks.stream().sorted(Comparator.comparingInt(a -> containerSlotOf(pi, a.getId()))).toList();

        runtimeService.completeUserTask(tasks.get(2).getId(), List.of());
        assertThat(containerStatus(containerOf(tasks.get(2).getId())))
            .as("finished user-task branch completes its own container")
            .isEqualTo(ActivityStatus.COMPLETED);

        runtimeService.completeUserTask(tasks.get(0).getId(), List.of());
        assertThat(containerStatus(containerOf(tasks.get(0).getId())))
            .isEqualTo(ActivityStatus.COMPLETED);

        runtimeService.completeUserTask(tasks.get(1).getId(), List.of());
        assertThat(activities(pi, "subProbe", ActivityStatus.COMPLETED)).hasSize(3);
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertThat(liveActivities(pi)).as("no live activities left after process end").isZero();
        assertThat(incidents(pi)).isZero();
    }

    @Transactional
    @Test
    void boundaryTimerOnContainer_isInertAfterInstanceCompletes() throws Exception {
        UUID pdId = deploy("test-c843-orphan-boundary-timer.bpmn");
        UUID pi = start(pdId);
        List<ActivityEntity> w = activities(pi, "w", ActivityStatus.CREATED);
        assertThat(w).hasSize(2);
        // По одному расписанию на инстанс-контейнер.
        assertThat(timerJobRepository.findAll().stream()
            .filter(j -> j.getProcessInstanceId().equals(pi) && "bndTmr".equals(j.getBoundaryElementId()))
            .count()).as("one timer job per MI container instance").isEqualTo(2);

        UUID firstContainer = containerOf(w.get(0).getId());
        runtimeService.completeServiceTask(w.get(0).getId(), List.of());
        assertThat(containerStatus(firstContainer)).isEqualTo(ActivityStatus.COMPLETED);

        // Расписание завершённого инстанса инертно: выстрел — no-op, без воскрешения и инцидента.
        activityService.fireBoundaryTimer(firstContainer, "bndTmr");
        assertThat(containerStatus(firstContainer))
            .as("firing the boundary of a completed instance must not resurrect it")
            .isEqualTo(ActivityStatus.COMPLETED);
        assertThat(activities(pi, "w", ActivityStatus.CREATED)).hasSize(1);
        assertThat(incidents(pi)).isZero();

        runtimeService.completeServiceTask(w.get(1).getId(), List.of());
        List<ActivityEntity> dump = activities(pi, "dump", ActivityStatus.CREATED);
        assertThat(dump).hasSize(1);
        runtimeService.completeServiceTask(dump.get(0).getId(), List.of());
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNotNull();
        assertThat(liveActivities(pi)).as("no live activities left after process end").isZero();
    }
}
