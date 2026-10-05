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

/**
 * WO-C8-35 раунд 3 (HOLD-fix), ШАГ 1: единое правило готовности inclusive-join для ВСЕХ
 * топологий — статический счётчик {@code expected} убран (решение CTO на M1+M2), поэтому
 * «ждать остальные ветви» обязано выводиться из состояния инстанса.
 *
 * <p>Диаграмма этого класса — pass-through фанаут: обе ветви идут в join БЕЗ wait-state
 * между сплитом и join'ом. Это единственная топология, где «кто ещё может доставить» не
 * выводится из активных activity: у ещё не запущенной ветви строки activity нет вообще.
 * Ровно здесь снятие счётчика обязано НЕ превратиться в срабатывание на первом приходе
 * (тогда вторая ветвь сработает вторым проходом — тот самый BLOCKER-1 red-team, где
 * {@code taskNotify} создавался дважды).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class InclusiveJoinReadinessIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityRepository activityRepository;

    private UUID start(String file) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + file));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        IdDTO started = runtimeService.startProcessInstance(dto);
        return started.getId();
    }

    private List<ActivityEntity> activities(UUID pi) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .toList();
    }

    private long countOf(UUID pi, String elementId, ActivityStatus status) {
        return activities(pi).stream()
            .filter(a -> a.getBpmnElementId().equals(elementId))
            .filter(a -> a.getStatus() == status)
            .count();
    }

    @Transactional
    @Test
    void passthroughFanOut_inclusiveJoinPassesThroughExactlyOnce() throws Exception {
        UUID pi = start("test-c835-passthrough-fanout.bpmn");

        // The join fired exactly ONCE for the two-branch wave: the downstream user task
        // exists exactly once. Two arrivals -> two firings would create it twice.
        assertThat(countOf(pi, "join", ActivityStatus.COMPLETED))
            .as("the join must pass through EXACTLY ONCE for the whole instance")
            .isEqualTo(1L);
        assertThat(countOf(pi, "taskAfter", ActivityStatus.CREATED))
            .as("downstream of the join must be entered exactly once (no double side effect)")
            .isEqualTo(1L);

        // Both branches really did arrive — the join is not "cheating" by firing early on
        // a single arrival with the other branch discarded.
        assertThat(activities(pi))
            .filteredOn(a -> a.getBpmnElementId().equals("fA") || a.getBpmnElementId().equals("fB"))
            .filteredOn(a -> a.getStatus() == ActivityStatus.COMPLETED)
            .as("both split branches traversed their flow")
            .hasSize(2);
    }

    @Transactional
    @Test
    void passthroughFanOut_joinWaitsForBothBranches_arrivalsAreBothRecorded() throws Exception {
        UUID pi = start("test-c835-passthrough-fanout.bpmn");

        // The instance stays RUNNING with exactly one live wait state — the downstream task.
        ProcessInstance instance = queryService.getProcessInstance(pi);
        assertThat(instance.getCompletedAt()).isNull();
        assertThat(activities(pi))
            .filteredOn(a -> a.getStatus() == ActivityStatus.CREATED)
            .filteredOn(a -> a.getBpmnElementId().equals("taskAfter"))
            .hasSize(1);
    }
}