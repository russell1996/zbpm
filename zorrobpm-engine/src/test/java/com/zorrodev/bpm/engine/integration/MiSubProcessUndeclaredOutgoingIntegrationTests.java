package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-44 — ранний выход MI-подпроцесса по {@code completionCondition}, когда у
 * подпроцесса НЕ объявлен {@code <bpmn:outgoing>} (граф задан только
 * {@code sequenceFlow sourceRef/targetRef} — валидный BPMN 2.0 и Camunda 8,
 * рукописные/сгенерированные схемы).
 *
 * <p>Фикстура — точный XML консьюмера (инстанс {@code 0e2d972c…}, определение
 * {@code miSubProcessCC} v5): ранний выход молча замораживал процесс
 * ({@code completedAt=null}, {@code flowSubDone} не создан, 0 инцидентов), а та
 * же схема с объявленными {@code incoming/outgoing} завершалась. Корень —
 * {@code FlowNavigator.proceedToOutgoing} идёт только по объявленному
 * {@code element.getOutgoing()} и видит мёртвый конец.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class MiSubProcessUndeclaredOutgoingIntegrationTests {

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
    private com.zorrodev.bpm.engine.handler.ElementSupport elementSupport;

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

    private long incidents(UUID pi) {
        var activityIds = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .map(ActivityEntity::getId)
            .collect(Collectors.toSet());
        return incidentRepository.findAll().stream()
            .filter(i -> activityIds.contains(i.getActivityId()))
            .count();
    }

    /** Merge-read видимых переменных задачи — тот же chain-контекст, что движок. */
    private Map<String, String> visible(UUID pi, UUID activityId) {
        Map<String, String> result = new LinkedHashMap<>();
        for (ProcessVariable v : elementSupport.visibleVariables(pi, activityId)) {
            result.put(v.getName(), v.getValue());
        }
        return result;
    }

    /**
     * Критерий 1 (RED-база WO-C8-44): точный XML консьюмера без объявленного
     * {@code <bpmn:outgoing>} у {@code subProbe} — сдача задачи {@code b}
     * завершает процесс ранним выходом: {@code flowSubDone}/{@code endRepro}
     * COMPLETED, {@code results=[null,"b",null]}, соседи CANCELLED, живых
     * activity нет (G-P leak-invariant), инцидентов 0.
     */
    @Transactional
    @Test
    void earlyExitWithoutDeclaredOutgoing_completesProcess() throws Exception {
        UUID pdId = deploy("test-c844-mi-sub-no-declared-outgoing.bpmn");
        UUID pi = start(pdId);

        List<ActivityEntity> tasks = activities(pi, "utProbe", ActivityStatus.CREATED);
        assertThat(tasks).as("3 parallel MI-subprocess instances").hasSize(3);

        ActivityEntity winner = tasks.stream()
            .filter(a -> "b".equals(visible(pi, a.getId()).get("item")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No utProbe instance with item=b"));
        runtimeService.completeUserTask(winner.getId(), List.of());

        assertThat(queryService.getProcessInstance(pi).getCompletedAt())
            .as("process completes after early exit (no declared outgoing)").isNotNull();
        assertThat(activities(pi, "flowSubDone", ActivityStatus.COMPLETED))
            .as("flowSubDone traversed").hasSize(1);
        assertThat(activities(pi, "endRepro", ActivityStatus.COMPLETED))
            .as("endRepro reached").hasSize(1);
        assertThat(activities(pi, "subProbe", ActivityStatus.COMPLETED))
            .as("MI-subprocess container completed").hasSize(1);
        assertThat(activities(pi, "subProbe", ActivityStatus.CANCELLED))
            .as("terminated siblings cancelled").hasSize(2);

        ProcessVariableEntity results = variableRepository
            .findByNameAndProcessInstanceId("results", pi).orElseThrow();
        List<String> values = new ObjectMapper().readValue(results.getTextValue(),
            new tools.jackson.core.type.TypeReference<List<String>>() {});
        assertThat(values).as("completed slot holds b, terminated slots stay null")
            .containsExactly(null, "b", null);

        // G-P leak-invariant: после завершения/отмены инстанса не остаётся
        // activity в CREATED/IN_PROGRESS, живых токенов ветвей нет.
        assertThat(activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED
                || a.getStatus() == ActivityStatus.IN_PROGRESS)
            .toList()).as("no live activities remain").isEmpty();
        assertThat(incidents(pi)).as("no incidents").isZero();
    }
}
