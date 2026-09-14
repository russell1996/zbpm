package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.handler.ElementSupport;
import com.zorrodev.bpm.engine.handler.FlowNavigator;
import com.zorrodev.bpm.engine.handler.TokenExecutor;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-REL-30: та же доменная граница, но на реальном PostgreSQL (V11/G-N).
 *
 * <p>H2 молча проглатывает то, что PG отвергает (FOR UPDATE вне Tx, FK, типы):
 * T1 (rollback посередине старта) и T3 (пропавший токен) обязаны быть зелёными
 * именно на PG. T2 (подсчёт SQL-стейтментов через H2-логгер) здесь не дублируется —
 * число стейтментов диалекто-независимо, а сам FOR UPDATE на PG доказан уже тем,
 * что T1/T3 и весь PG-набор идут через {@code findByIdForUpdate} без
 * {@code TransactionRequiredException}. Класс намеренно БЕЗ {@code @Transactional} (P-18).
 */
@Tag("pg")
class DomainTransactionalBoundaryPgIT extends PostgresIT {

    @Autowired ProcessDefinitionService processDefinitionService;
    @Autowired RuntimeService runtimeService;
    @Autowired ActivityService activityService;
    @Autowired ElementSupport elementSupport;
    @Autowired FlowNavigator flowNavigator;
    @Autowired BpmnService bpmnService;
    @Autowired ActivityRepository activityRepository;
    @Autowired ProcessInstanceRepository processInstanceRepository;
    @Autowired QueryService queryService;

    private UUID deployServiceTaskProcess() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/integration/process1.bpmn"));
        String randomKey = "reltxpg" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String keyed = bpmn.replace("Process_1lkt6gs", randomKey);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(keyed);
        return model.getId();
    }

    @Test
    void startProcessInstance_failsAfterCreate_rollsBackFully() throws Exception {
        UUID pdId = deployServiceTaskProcess();
        long before = processInstanceRepository.count();

        assertThatThrownBy(() -> activityService.startProcessInstanceFromStartEvent(pdId, "no-such-element", List.of()))
            .as("старт с несуществующим элементом обязан упасть (падение в execute, после create)")
            .isInstanceOf(RuntimeException.class);

        assertThat(processInstanceRepository.count())
            .as("PG: сбой посередине обязан дать полный rollback — висячих инстансов нет")
            .isEqualTo(before);
    }

    @Test
    void finishBranch_missingToken_managedBranchNot500() throws Exception {
        UUID pdId = deployServiceTaskProcess();
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        dto.setVariables(List.of());
        UUID piId = runtimeService.startProcessInstance(dto).getId();
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(pdId);

        assertThatCode(() -> flowNavigator.finishBranch(piId, UUID.randomUUID(), bpmn, Mockito.mock(TokenExecutor.class)))
            .as("PG: пропавший токен — управляемая ветка, не исключение")
            .doesNotThrowAnyException();

        assertThat(queryService.getProcessInstance(piId).getCompletedAt())
            .as("PG: fail-closed — инстанс не завершается по несуществующему токену")
            .isNull();
    }

    @Test
    void lockAndReload_happyPathOnPg() throws Exception {
        UUID pdId = deployServiceTaskProcess();
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        dto.setVariables(List.of());
        UUID piId = runtimeService.startProcessInstance(dto).getId();
        List<ActivityEntity> active = activityRepository.findByProcessInstanceIdAndStatusIn(
            piId, List.of(ActivityStatus.CREATED, ActivityStatus.IN_PROGRESS));
        assertThat(active).as("PG: запаркованная service task").isNotEmpty();

        // Один SELECT ... FOR UPDATE под капотом: на PG без активной Tx это
        // кинуло бы TransactionRequiredException — join-аннотация открывает свою.
        assertThat(elementSupport.lockAndReload(active.get(0).getId())).isNotNull();
    }

    @Test
    void runtimeStart_happyPathOnPg() throws Exception {
        UUID pdId = deployServiceTaskProcess();
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        dto.setVariables(List.of());
        UUID piId = runtimeService.startProcessInstance(dto).getId();

        assertThat(queryService.getProcessInstance(piId)).isNotNull();
        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();
    }
}
