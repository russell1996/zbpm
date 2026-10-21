package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-OPS-35 (path 3): низкоуровневый примитив отмены {@code DBService.cancelProcessInstance}
 * САМОДОСТАТОЧЕН — он гасит activity самого инстанса.
 *
 * <p>До правки это делал ВЫЗЫВАЮЩИЙ: REST-путь
 * ({@code ProcessInstanceRuntimeOperationsImpl.cancelProcessInstance}) звал
 * {@code cancelActiveActivities} перед отменой, а прямые вызовы примитива из движка
 * (deferred-tail canceling-фазы, {@code CompletionService}) и из кода — нет. Инстанс
 * помечался отменённым, а его задачи оставались ЖИВЫМИ: воркер потом получал job по
 * мёртвому инстансу, а инвариант утечек (WO-OPS-26/32) ловил остаток как
 * {@code type=activity element=taskM live activity} + {@code type=task element=taskM open user_task}.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class CancelProcessInstancePrimitiveIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private DBService dbService;

    @Autowired
    private ActivityRepository activityRepository;

    private UUID startParkedOnTaskM() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-metrics-park.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of());
        return runtimeService.startProcessInstance(dto).getId();
    }

    private ActivityStatus activityStatus(UUID processInstanceId, String bpmnElementId) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .filter(a -> bpmnElementId.equals(a.getBpmnElementId()))
            .map(ActivityEntity::getStatus)
            .findFirst().orElse(null);
    }

    @Transactional
    @Test
    void cancelProcessInstance_terminatesLiveActivitiesOfTheInstance() throws Exception {
        UUID pi = startParkedOnTaskM();
        assertThat(activityStatus(pi, "taskM")).as("task parked").isEqualTo(ActivityStatus.CREATED);

        dbService.cancelProcessInstance(pi);

        // the instance is terminal …
        assertThat(queryService.getProcessInstance(pi).isCancelled()).isTrue();
        // … and its live task died with it (WO-OPS-35): no live activity in a
        // terminal instance, no job for a worker on a dead instance.
        assertThat(activityStatus(pi, "taskM")).isEqualTo(ActivityStatus.CANCELLED);
    }
}
