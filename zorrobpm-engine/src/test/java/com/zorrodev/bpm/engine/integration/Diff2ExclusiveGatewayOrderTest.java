package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-DIFF-2 (Raxon diff-finding №2, WO-014 сценарий S-016-exclusive-happy):
 * при нескольких одновременно истинных условиях на исходящих потоках
 * exclusive gateway настоящий Zeebe берёт ПОСЛЕДНИЙ по документному порядку
 * (reverse document order — тот же механизм, что fork-take в parallel gateway,
 * Raxon WO-013), а Zorro брал ПЕРВЫЙ (forward).
 *
 * <p>Живые данные из находки: {@code amount=150}, оба условия true
 * ({@code big}: {@code amount > 100}, {@code small}: {@code amount < 200})
 * → Zeebe: {@code chosen=small}; Zorro (до фикса): {@code chosen=big}.
 * Здесь {@code chosen=big} = взят {@code endBig}, {@code chosen=small} =
 * взят {@code endSmall} (концевые события вместо service task'ов — маршрут
 * виден синхронно, без job-воркеров).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class Diff2ExclusiveGatewayOrderTest {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityRepository activityRepository;

    private ProcessVariable amount(String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName("amount");
        v.setType(ProcessVariableType.LONG);
        v.setValue(value);
        return v;
    }

    private UUID startWithAmount(String amountValue) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-diff2-gateway-order.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of(amount(amountValue)));
        return runtimeService.startProcessInstance(dto).getId();
    }

    private Set<String> completedEnds(UUID processInstanceId) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(processInstanceId))
            .filter(a -> a.getStatus() == ActivityStatus.COMPLETED)
            .map(ActivityEntity::getBpmnElementId)
            .collect(Collectors.toSet());
    }

    @Transactional
    @Test
    void bothConditionsTrue_takesLastInDocumentOrder() throws Exception {
        // amount=150: оба условия true → Zeebe берёт последний по документному
        // порядку (flowSmall -> endSmall, chosen=small).
        UUID processInstanceId = startWithAmount("150");

        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNotNull();

        assertThat(completedEnds(processInstanceId))
            .as("amount=150, both true -> chosen=small (endSmall taken)")
            .contains("endSmall");
        assertThat(completedEnds(processInstanceId))
            .as("amount=150, both true -> endBig must NOT be taken")
            .doesNotContain("endBig");
    }

    @Transactional
    @Test
    void onlyFirstConditionTrue_takesFirst() throws Exception {
        // amount=500: true только flowBig (первый) → берётся он, несмотря на
        // позицию. Ловит "фикс", который вслепую берёт последний поток без
        // вычисления условий.
        UUID processInstanceId = startWithAmount("500");

        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNotNull();

        assertThat(completedEnds(processInstanceId))
            .as("amount=500, only flowBig true -> endBig taken")
            .contains("endBig");
        assertThat(completedEnds(processInstanceId))
            .as("amount=500, only flowBig true -> endSmall must NOT be taken")
            .doesNotContain("endSmall");
    }

    @Transactional
    @Test
    void onlySecondConditionTrue_takesSecond() throws Exception {
        // amount=5: true только flowSmall (последний) → берётся он.
        UUID processInstanceId = startWithAmount("5");

        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNotNull();

        assertThat(completedEnds(processInstanceId))
            .as("amount=5, only flowSmall true -> endSmall taken")
            .contains("endSmall");
        assertThat(completedEnds(processInstanceId))
            .as("amount=5, only flowSmall true -> endBig must NOT be taken")
            .doesNotContain("endBig");
    }
}
