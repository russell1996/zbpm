package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import com.zorrodev.bpm.engine.service.DBService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-REL-28: guard against duplicate service task after manual completion.
 *
 * Each step runs in its OWN transaction via TransactionTemplate — mirroring
 * the real per-HTTP-request transaction so committed state is visible across
 * steps. Assertions are scoped to this test's own process instance.
 *
 * <p><b>WO-OPS-25 (2026-10-20):</b> класс был переименован из
 * {@code IncidentResolveAfterManualCompleteIT} — суффикс {@code *IT.java} не входит
 * ни в surefire-дефолт, ни в failsafe-includes engine-pom, поэтому класс НИ РАЗУ не
 * выполнялся в CI и к моменту переименения протух: первый тест падал, потому что
 * контракт «resolve на завершённом инстансе» изменился в WO-C8-49 (закрытый инстанс
 * не воскрешаем — 409 CONFLICT, {@code IncidentService.resolveIncident:114}).
 *
 * <p>Класс разделён на два живых сценария:
 * <ul>
 *   <li>{@code resolveAfterManualComplete_doesNotDuplicateServiceTask} — тот же
 *       исходный сценарий (сервисная задача ПОСЛЕДНЯЯ в процессе ⇒ инстанс завершается
 *       обычным end event). Нынешний контракт: resolve отклоняется с 409, дубликата
 *       активности не появляется;</li>
 *   <li>{@code resolveAfterManualCompleteOnLiveInstance_closesIncidentWithoutReexecution} —
 *       живой инстанс (сервисная задача в середине потока ⇒ после ручного завершения
 *       инстанс жив). Здесь действует ветка WO-REL-28 «activity COMPLETED ⇒ инцидент
 *       закрывается БЕЗ повторного исполнения» — её закрывает именно этот класс.</li>
 * </ul>
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class IncidentResolveAfterManualCompleteIntegrationTests {

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private DBService dbService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
    }

    private long countByTokenAndElement(UUID token, String elementId) {
        return tx.execute(s -> (long) activityRepository.findByTokenAndBpmnElementId(token, elementId).size());
    }

    private long countActiveByTokenAndElement(UUID token, String elementId) {
        return tx.execute(s -> activityRepository
            .findByTokenAndBpmnElementIdAndStatusIn(token, elementId,
                List.of(ActivityStatus.CREATED, ActivityStatus.IN_PROGRESS)).size());
    }

    private long countOpenIncidents(UUID activityId) {
        return tx.execute(s -> (long) incidentRepository.findByActivityIdInAndCompletedAtIsNull(List.of(activityId)).size());
    }

    /** Старт инстанса и поиск живой сервисной задачи {@code svc}. */
    private record Started(UUID processInstanceId, UUID svcId, UUID token) { }

    private Started startAndFindSvc(String bpmnFile) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + bpmnFile));
        UUID pi = tx.execute(s -> {
            ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
            StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
            dto.setProcessDefinitionId(model.getId());
            return runtimeService.startProcessInstance(dto).getId();
        });
        ActivityEntity svc = tx.execute(s -> activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi) && a.getBpmnElementId().equals("svc") && a.getStatus() == ActivityStatus.CREATED)
            .findFirst().orElseThrow());
        return new Started(pi, svc.getId(), svc.getToken());
    }

    @Test
    void resolveAfterManualComplete_doesNotDuplicateServiceTask() throws Exception {
        // svc — ПОСЛЕДНЯЯ задача процесса ⇒ ручное завершение закрывает инстанс.
        Started started = startAndFindSvc("test-service-task-fail.bpmn");
        UUID pi = started.processInstanceId();
        UUID svcId = started.svcId();
        UUID token = started.token();

        // Watchdog-style incident on the still-CREATED activity
        UUID incidentId = tx.execute(s -> dbService.createIncident(svcId, "SERVICE_TASK_DISPATCH_TIMEOUT: test"));

        // Operator manually completes the service task (admin override path)
        tx.executeWithoutResult(s -> runtimeService.completeServiceTask(svcId, List.of()));

        // Verify manual completion left exactly one activity for (token,svc) and it is COMPLETED
        long afterComplete = countByTokenAndElement(token, "svc");
        assertThat(afterComplete).as("exactly one activity after manual complete").isEqualTo(1);
        ActivityStatus statusAfter = tx.execute(s -> activityRepository.findById(svcId).orElseThrow().getStatus());
        assertThat(statusAfter).isEqualTo(ActivityStatus.COMPLETED);
        IncidentEntity beforeResolve = tx.execute(s -> incidentRepository.findById(incidentId).orElseThrow());
        assertThat(beforeResolve.getCompletedAt()).as("incident still open before resolve").isNull();

        // WO-C8-49: инстанс завершён ⇒ закрытый инстанс не воскрешаем — resolve отклоняется
        // (409 CONFLICT), а не переисполняет элемент. Прежде (до WO-C8-49) этот шаг
        // закрывал инцидент без повторного исполнения — и ровно это поведение теперь
        // проверяет resolveAfterManualCompleteOnLiveInstance_closesIncidentWithoutReexecution.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> runtimeService.resolveIncident(incidentId, List.of())))
            .as("resolve на завершённом инстансе — отказ (WO-C8-49), а не перезапуск")
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT));

        // Инвариант исходного теста (WO-REL-28) остаётся: дубликата активности нет.
        long afterResolve = countByTokenAndElement(token, "svc");
        assertThat(afterResolve).as("must remain exactly one activity after resolve (no duplicate)").isEqualTo(1);
        assertThat(countActiveByTokenAndElement(token, "svc"))
            .as("no new active activity was created").isZero();
        assertThat(countOpenIncidents(svcId))
            .as("отказ не создал новый инцидент; исходный остался открытым — обычный end event "
                + "не финализирует инциденты (см. находку V7 в отчёте WO-OPS-25)").isEqualTo(1);
        Instant completedAtAfterReject =
            tx.execute(s -> incidentRepository.findById(incidentId).orElseThrow().getCompletedAt());
        assertThat(completedAtAfterReject).as("исходный инцидент не закрыт отказом").isNull();
    }

    @Test
    void resolveAfterManualCompleteOnLiveInstance_closesIncidentWithoutReexecution() throws Exception {
        // svc в середине потока ⇒ после ручного завершения инстанс ЖИВ (стоит на user task).
        Started started = startAndFindSvc("test-eng-23-svc-then-wait.bpmn");
        UUID svcId = started.svcId();
        UUID token = started.token();

        // Watchdog-style incident on the still-CREATED activity
        UUID incidentId = tx.execute(s -> dbService.createIncident(svcId, "SERVICE_TASK_DISPATCH_TIMEOUT: test"));

        // Operator manually completes the service task (admin override path)
        tx.executeWithoutResult(s -> runtimeService.completeServiceTask(svcId, List.of()));

        long afterComplete = countByTokenAndElement(token, "svc");
        assertThat(afterComplete).as("exactly one activity after manual complete").isEqualTo(1);
        ActivityStatus statusAfterComplete =
            tx.execute(s -> activityRepository.findById(svcId).orElseThrow().getStatus());
        assertThat(statusAfterComplete).isEqualTo(ActivityStatus.COMPLETED);

        // Resolve — ветка WO-REL-28: активность задачи уже COMPLETED ⇒ инцидент закрывается
        // БЕЗ повторного исполнения (никакой новой активности не создаётся).
        tx.executeWithoutResult(s -> runtimeService.resolveIncident(incidentId, List.of()));

        long afterResolve = countByTokenAndElement(token, "svc");
        assertThat(afterResolve).as("must remain exactly one activity after resolve (no duplicate)").isEqualTo(1);
        assertThat(countActiveByTokenAndElement(token, "svc"))
            .as("no new active activity was created").isZero();
        IncidentEntity afterResolveInc = tx.execute(s -> incidentRepository.findById(incidentId).orElseThrow());
        assertThat(afterResolveInc.getCompletedAt()).as("incident must be closed without re-execution").isNotNull();
        assertThat(countOpenIncidents(svcId)).as("открытых инцидентов не осталось").isZero();
    }

    @Test
    void normalIncidentResolve_reExecutesWhenNotCompleted() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-service-task-fail.bpmn"));
        UUID pi = tx.execute(s -> {
            ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
            StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
            dto.setProcessDefinitionId(model.getId());
            return runtimeService.startProcessInstance(dto).getId();
        });

        ActivityEntity svc = tx.execute(s -> activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi) && a.getBpmnElementId().equals("svc") && a.getStatus() == ActivityStatus.CREATED)
            .findFirst().orElseThrow());
        UUID svcId = svc.getId();
        UUID token = svc.getToken();

        // Fail fatally -> incident on ERROR activity
        tx.executeWithoutResult(s -> runtimeService.failServiceTask(svcId, "boom", 0));
        UUID incidentId = tx.execute(s -> incidentRepository.findByActivityIdInAndCompletedAtIsNull(List.of(svcId)).get(0).getId());

        // Resolve — worker has recovered, should re-execute (create new active)
        tx.executeWithoutResult(s -> runtimeService.resolveIncident(incidentId, List.of()));

        long activeAfter = tx.execute(s -> activityRepository.findByTokenAndBpmnElementIdAndStatusIn(token, "svc", List.of(ActivityStatus.CREATED, ActivityStatus.IN_PROGRESS)).size());
        assertThat(activeAfter).as("normal resolve must create one active activity").isEqualTo(1);
        IncidentEntity after = tx.execute(s -> incidentRepository.findById(incidentId).orElseThrow());
        assertThat(after.getCompletedAt()).isNotNull();
    }
}
