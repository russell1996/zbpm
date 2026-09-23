package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import com.zorrodev.bpm.engine.service.impl.ScriptServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-DIFF-10: exclusive gateway, у которого исходящий поток БЕЗ conditionExpression
 * и НЕ default. До фикса {@code FlowNavigator.processFlow} вызывал
 * {@code scriptService.evaluateScript(null, ...)}, а маскирующий {@code codeRef(null)}
 * падал своей NPE ({@code Cannot invoke "String.length()" because "code" is null}) —
 * инцидент никогда не резолвился, т.к. причина в модели.
 *
 * <p>После фикса: поток без условия просто не матчится (как "условие ложно"), цикл в
 * {@code ExclusiveGatewayHandler} идёт дальше и падает в существующий чистый
 * {@code IllegalStateException} ("no outgoing sequence flow condition was true...").
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class Diff10GatewayNoConditionNpeTest {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private IncidentRepository incidentRepository;

    private ProcessVariable action(String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName("action");
        v.setType(ProcessVariableType.STRING);
        v.setValue(value);
        return v;
    }

    private UUID start(String bpmnFile, List<ProcessVariable> variables) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + bpmnFile));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(variables);
        return runtimeService.startProcessInstance(dto).getId();
    }

    private List<IncidentEntity> incidentsOn(UUID processInstanceId, String bpmnElementId) {
        ActivityEntity gateway = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .filter(a -> a.getBpmnElementId().equals(bpmnElementId))
            .findFirst().orElseThrow();
        return incidentRepository.findAll().stream()
            .filter(i -> i.getActivityId().equals(gateway.getId()))
            .toList();
    }

    @Transactional
    @Test
    void unconditionalFlows_raiseCleanIncident_notNpe() throws Exception {
        // Оба исходящих без условия, default нет: ни один поток не матчится,
        // гейтвей обязан упасть в чистый IllegalStateException, а не в NPE.
        UUID processInstanceId = start("test-diff10-gateway-no-condition.bpmn", List.of(action("anything")));

        // инстанс запаркован (не завершён), на гейтвее ровно один инцидент
        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNull();
        List<IncidentEntity> incidents = incidentsOn(processInstanceId, "xor");
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getMessage())
            .contains("no outgoing sequence flow condition was true")
            .doesNotContain("NullPointerException")
            .doesNotContain("String.length()");
    }

    @Transactional
    @Test
    void mixedConditionalAndUnconditional_routesToMatchingCondition() throws Exception {
        // Один поток с истинным условием + один без условия: безусловный просто
        // не матчится, условный маршрутизируется нормально, инстанс завершается.
        UUID processInstanceId = start("test-diff10-gateway-mixed.bpmn", List.of(action("A")));

        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNotNull();
        List<ActivityEntity> activities = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .toList();
        assertThat(activities).anyMatch(a -> a.getBpmnElementId().equals("endC") && a.getStatus() == ActivityStatus.COMPLETED);
        assertThat(activities).noneMatch(a -> a.getBpmnElementId().equals("endU"));
        assertThat(incidentsOn(processInstanceId, "xor")).isEmpty();
    }

    @Test
    void codeRefNull_returnsMeaningfulString_notNpe() throws Exception {
        // Критерий 3: defense-in-depth — codeRef(null) не бросает NPE.
        // Мутация, снимаемая только этим фиксом: убери null-guard в codeRef —
        // этот тест обязан покраснеть с NullPointerException.
        Method codeRef = ScriptServiceImpl.class.getDeclaredMethod("codeRef", String.class);
        codeRef.setAccessible(true);
        Object result = codeRef.invoke(null, (Object) null);
        assertThat(result).isNotNull();
        assertThat(result.toString()).contains("null");
    }
}
