package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.dto.IdDTO;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.service.ActivityService;
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
 * WO-C8-38 (C38-3): границы отмены — scope-confinement для вложенного
 * прерывающего event sub-process.
 *
 * <p>Прерывающий ESP, лексически вложенный в подпроцесс, по scope-семантике
 * (принцип C8-34) гасит свой РОДИТЕЛЬСКИЙ scope, а не весь инстанс: параллельные
 * ветви ВНЕ scope живут дальше. Старое поведение ({@code cancelActiveActivities}
 * всего инстанса) молча теряло несвязанную ветвь: инстанс уходил в COMPLETED
 * концом ESP, а внешняя задача отменялась.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class NestedEventSubprocessCancelScopeIntegrationTests {

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private QueryService queryService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private ActivityService activityService;

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
            .filter(a -> elementId.equals(a.getBpmnElementId()))
            .filter(a -> a.getStatus() == status)
            .count();
    }

    private long incidents(UUID pi) {
        List<UUID> ids = activities(pi).stream().map(ActivityEntity::getId).toList();
        return incidentRepository.findAll().stream().filter(i -> ids.contains(i.getActivityId())).count();
    }

    @Transactional
    @Test
    void nestedInterruptingEsp_cancelsOnlyItsParentScope() throws Exception {
        UUID pi = start("test-c838-nested-esp-cancels-scope.bpmn");

        assertThat(countOf(pi, "taskIn", ActivityStatus.CREATED))
            .as("premise: the scope task is live").isEqualTo(1L);
        assertThat(countOf(pi, "taskOutside", ActivityStatus.CREATED))
            .as("premise: the outside branch is live").isEqualTo(1L);

        activityService.correlateMessage("mGo", pi, List.of());

        assertThat(countOf(pi, "taskIn", ActivityStatus.CANCELLED))
            .as("the parent scope's task dies with the scope").isEqualTo(1L);
        assertThat(countOf(pi, "taskIdle", ActivityStatus.CANCELLED))
            .as("the scope sibling dies with the scope").isEqualTo(1L);
        assertThat(countOf(pi, "taskOutside", ActivityStatus.CREATED))
            .as("the branch OUTSIDE the scope survives the nested ESP trigger")
            .isEqualTo(1L);
        assertThat(countOf(pi, "espTask", ActivityStatus.CREATED))
            .as("the ESP handler itself starts").isEqualTo(1L);
        assertThat(queryService.getProcessInstance(pi).getCompletedAt())
            .as("the instance is NOT completed by the ESP end — an outside branch is still live")
            .isNull();
        assertThat(incidents(pi)).isEqualTo(0L);
    }
}
