package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
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
 * WO-C8-39: отмена транзакции через cancel-конец на форк-ветви внутри
 * {@code <transaction>} — тихий no-op (находка V7 из WO-C8-38).
 *
 * <p>Токен cancel-конца — дитя scope-токена БЕЗ {@code scopeActivityId}
 * (форк: {@code ParallelGatewayHandler} — {@code createToken(tokenId)}),
 * поэтому {@code CancelEndHandler} на коде до правки уходит в ветку
 * «outside a transaction scope»: транзакция НЕ гасится, cancel-граница НЕ
 * продолжается. Живой прогон, не ручная сборка состояния.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class ForkCancelInTransactionIntegrationTests {

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private VariableRepository variableRepository;

    private UUID start(String file) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + file));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        ProcessVariable log = new ProcessVariable();
        log.setName("log");
        log.setType(ProcessVariableType.LONG);
        log.setValue("0");
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of(log));
        return runtimeService.startProcessInstance(dto).getId();
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

    private void complete(UUID pi, String elementId) {
        activities(pi).stream()
            .filter(a -> elementId.equals(a.getBpmnElementId()))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED)
            .findFirst()
            .ifPresentOrElse(
                a -> runtimeService.completeUserTask(a.getId(), List.of()),
                () -> { throw new AssertionError("no CREATED " + elementId + " in " + pi); });
    }

    @Transactional
    @Test
    void forkBranchCancelEnd_cancelsTransactionAndContinuesFromCancelBoundary() throws Exception {
        UUID pi = start("test-c839-fork-cancel-in-transaction.bpmn");
        // обе ветви припаркованы: taskA ждёт, trigB ждёт выстрела
        assertThat(countOf(pi, "taskA", ActivityStatus.CREATED)).isEqualTo(1L);
        assertThat(countOf(pi, "trigB", ActivityStatus.CREATED)).isEqualTo(1L);

        // соседняя ветвь завершается ПЕРВОЙ (её конец на форк-токене не закрывает
        // транзакцию): taskA COMPLETED — кандидат компенсации через scope-фильтр
        complete(pi, "taskA");
        assertThat(countOf(pi, "tx", ActivityStatus.CREATED))
            .as("premise: completing one fork branch does not close the transaction")
            .isEqualTo(1L);

        // выстрел cancel-конца с форк-ветви
        complete(pi, "trigB");

        // транзакция погашена целиком, поток продолжен с cancel-границы
        // (txHandler log += 100 за скомпенсированный taskA, afterCancel += 1000)
        assertThat(countOf(pi, "tx", ActivityStatus.CANCELLED))
            .as("the transaction container is cancelled by the fork-branch cancel end")
            .isEqualTo(1L);
        assertThat(countOf(pi, "txHandler", ActivityStatus.COMPLETED))
            .as("the completed sibling is compensated via the scope filter, not token equality")
            .isEqualTo(1L);
        assertThat(countOf(pi, "afterCancel", ActivityStatus.COMPLETED))
            .as("flow continues from the transaction cancel boundary")
            .isEqualTo(1L);
        ProcessVariableEntity result =
            variableRepository.findByNameAndProcessInstanceId("log", pi).orElseThrow();
        assertThat(result.getTextValue()).isEqualTo("1100");
        assertThat(incidents(pi)).as("no incident").isEqualTo(0L);
        assertThat(countOf(pi, "normalEnd", ActivityStatus.COMPLETED))
            .as("the transaction normal end is never taken")
            .isEqualTo(0L);
    }

    @Transactional
    @Test
    void nestedTransaction_forkCancelCancelsOnlyTheInnerTransaction() throws Exception {
        UUID pi = start("test-c839-nested-cancel-in-transaction.bpmn");
        assertThat(countOf(pi, "outerTask", ActivityStatus.CREATED)).isEqualTo(1L);
        assertThat(countOf(pi, "innerTask", ActivityStatus.CREATED)).isEqualTo(1L);
        assertThat(countOf(pi, "trigIn", ActivityStatus.CREATED)).isEqualTo(1L);

        // выстрел cancel-конца с форк-ветви ВНУТРЕННЕЙ транзакции
        complete(pi, "trigIn");

        // внутренняя погашена и продолжена со своей границы
        assertThat(countOf(pi, "inner", ActivityStatus.CANCELLED))
            .as("the inner transaction is cancelled by its own fork-branch cancel end")
            .isEqualTo(1L);
        assertThat(countOf(pi, "innerTask", ActivityStatus.CANCELLED))
            .as("the inner sibling fork branch is cancelled with it")
            .isEqualTo(1L);
        assertThat(countOf(pi, "afterInner", ActivityStatus.COMPLETED))
            .as("flow continues from the INNER transaction cancel boundary")
            .isEqualTo(1L);
        ProcessVariableEntity result =
            variableRepository.findByNameAndProcessInstanceId("log", pi).orElseThrow();
        assertThat(result.getTextValue()).isEqualTo("1000");
        // внешняя НЕ тронута: её ветвь жива, её граница не стреляла
        assertThat(countOf(pi, "outerTask", ActivityStatus.CREATED))
            .as("the outer transaction sibling branch stays alive")
            .isEqualTo(1L);
        assertThat(countOf(pi, "outer", ActivityStatus.CANCELLED))
            .as("the outer transaction is not cancelled")
            .isEqualTo(0L);
        assertThat(countOf(pi, "afterOuter", ActivityStatus.COMPLETED))
            .as("the outer cancel boundary never fires")
            .isEqualTo(0L);
        assertThat(incidents(pi)).as("no incident").isEqualTo(0L);
    }

    @Transactional
    @Test
    void rootLevelCancelEnd_keepsLegacyBranchEndBehaviour() throws Exception {
        UUID pi = start("test-c839-root-cancel-end.bpmn");
        assertThat(countOf(pi, "taskR", ActivityStatus.CREATED)).isEqualTo(1L);

        complete(pi, "taskR");

        // вне транзакции — прежнее тихое завершение ветви: без отмены,
        // без компенсации, без инцидента (по BPMN 2.0 такой конец невалиден,
        // движок его принимает — фиксируем как есть, НЕ чиним в этом WO).
        assertThat(countOf(pi, "rootCancel", ActivityStatus.COMPLETED))
            .as("the root cancel end element itself completes")
            .isEqualTo(1L);
        assertThat(incidents(pi)).as("no incident").isEqualTo(0L);
    }

    @Transactional
    @Test
    void miSibling_forkCancelCancelsBothCopiesWithTheScope() throws Exception {
        UUID pi = start("test-c839-mi-cancel-in-transaction.bpmn");
        assertThat(countOf(pi, "miTask", ActivityStatus.CREATED))
            .as("both MI copies are parked")
            .isEqualTo(2L);
        assertThat(countOf(pi, "trigB", ActivityStatus.CREATED)).isEqualTo(1L);

        // выстрел cancel-конца с форк-ветви
        complete(pi, "trigB");

        // scope-отмена покрывает MI-копии через цепочку токенов
        assertThat(countOf(pi, "miTask", ActivityStatus.CANCELLED))
            .as("both MI copies are cancelled with the transaction scope")
            .isEqualTo(2L);
        assertThat(countOf(pi, "tx", ActivityStatus.CANCELLED)).isEqualTo(1L);
        assertThat(countOf(pi, "afterCancel", ActivityStatus.COMPLETED)).isEqualTo(1L);
        ProcessVariableEntity result =
            variableRepository.findByNameAndProcessInstanceId("log", pi).orElseThrow();
        assertThat(result.getTextValue()).isEqualTo("1000");
        assertThat(incidents(pi)).as("no incident").isEqualTo(0L);
        assertThat(countOf(pi, "normalEnd", ActivityStatus.COMPLETED)).isEqualTo(0L);
    }

    @Transactional
    @Test
    void outerCancel_innerScopeDescendantsAreCancelledToo() throws Exception {
        UUID pi = start("test-c839-outer-cancel-in-transaction.bpmn");
        assertThat(countOf(pi, "outerTask", ActivityStatus.CREATED)).isEqualTo(1L);
        assertThat(countOf(pi, "innerTask", ActivityStatus.CREATED)).isEqualTo(1L);
        assertThat(countOf(pi, "trigOut", ActivityStatus.CREATED)).isEqualTo(1L);

        // выстрел cancel-конца с форк-ветви ВНЕШНЕЙ транзакции
        complete(pi, "trigOut");

        // scope-отмена покрывает и токены-потомки (живая внутренняя транзакция):
        // по BPMN 2.0 cancel-граница сначала прерывает ВСЕ исполнения scope
        assertThat(countOf(pi, "outer", ActivityStatus.CANCELLED))
            .as("the outer transaction is cancelled")
            .isEqualTo(1L);
        assertThat(countOf(pi, "inner", ActivityStatus.CANCELLED))
            .as("the live nested transaction scope is cancelled with it")
            .isEqualTo(1L);
        assertThat(countOf(pi, "innerTask", ActivityStatus.CANCELLED))
            .as("the activity on the descendant scope token is cancelled, not left dangling")
            .isEqualTo(1L);
        assertThat(countOf(pi, "outerTask", ActivityStatus.CANCELLED))
            .as("the sibling fork branch is cancelled")
            .isEqualTo(1L);
        assertThat(countOf(pi, "afterOuter", ActivityStatus.COMPLETED))
            .as("flow continues from the outer cancel boundary")
            .isEqualTo(1L);
        ProcessVariableEntity result =
            variableRepository.findByNameAndProcessInstanceId("log", pi).orElseThrow();
        assertThat(result.getTextValue()).isEqualTo("1000");
        assertThat(incidents(pi)).as("no incident").isEqualTo(0L);
        assertThat(countOf(pi, "normalEnd", ActivityStatus.COMPLETED)).isEqualTo(0L);
    }

    @Transactional
    @Test
    void completedInnerTransaction_compensatedOnOuterCancel() throws Exception {
        UUID pi = start("test-c839-completed-inner-compensated.bpmn");
        // внутренняя транзакция завершается штатно ДО выстрела
        assertThat(countOf(pi, "inner", ActivityStatus.COMPLETED))
            .as("premise: the inner transaction completes normally first")
            .isEqualTo(1L);
        assertThat(countOf(pi, "outerWait", ActivityStatus.CREATED)).isEqualTo(1L);
        assertThat(countOf(pi, "trigOut", ActivityStatus.CREATED)).isEqualTo(1L);

        // выстрел cancel-конца с форк-ветви внешней транзакции
        complete(pi, "trigOut");

        // успешно завершённое вложенное компенсируется (норма CIB seven),
        // живое — отменяется, поток — с внешней cancel-границы (1+100+1000)
        assertThat(countOf(pi, "outer", ActivityStatus.CANCELLED)).isEqualTo(1L);
        assertThat(countOf(pi, "txHandler2", ActivityStatus.COMPLETED))
            .as("the completed inner task is compensated via the scope filter")
            .isEqualTo(1L);
        assertThat(countOf(pi, "outerWait", ActivityStatus.CANCELLED)).isEqualTo(1L);
        assertThat(countOf(pi, "afterOuter", ActivityStatus.COMPLETED)).isEqualTo(1L);
        ProcessVariableEntity result =
            variableRepository.findByNameAndProcessInstanceId("log", pi).orElseThrow();
        assertThat(result.getTextValue()).isEqualTo("1101");
        assertThat(incidents(pi)).as("no incident").isEqualTo(0L);
    }
}
