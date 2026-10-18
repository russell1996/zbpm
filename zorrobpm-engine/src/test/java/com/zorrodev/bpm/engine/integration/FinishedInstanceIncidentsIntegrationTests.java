package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.query.IncidentQuery;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.MessageSubscriptionRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.SignalSubscriptionRepository;
import com.zorrodev.bpm.engine.repository.TimerJobRepository;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
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
 * WO-C8-49: отмена/завершение инстанса оставляет открытые инциденты и ERROR-активности;
 * resolve на закрытом инстансе перезапускает элемент (зомби-активность + зомби-инцидент,
 * прод-инстанс 9dcdd13e).
 *
 * <p>Каждый шаг — своя транзакция через {@link TransactionTemplate} (как реальный
 * per-request scope). Ассерты scoped на свой инстанс — общий H2 это терпит.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class FinishedInstanceIncidentsIntegrationTests {

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
    @Autowired
    private IncidentRepository incidentRepository;
    @Autowired
    private ProcessInstanceRepository processInstanceRepository;
    @Autowired
    private TimerJobRepository timerJobRepository;
    @Autowired
    private MessageSubscriptionRepository messageSubscriptionRepository;
    @Autowired
    private SignalSubscriptionRepository signalSubscriptionRepository;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
    }

    private UUID deployAndStart(String bpmnFile) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + bpmnFile));
        return tx.execute(s -> {
            ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
            StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
            dto.setProcessDefinitionId(model.getId());
            return runtimeService.startProcessInstance(dto).getId();
        });
    }

    private UUID activeActivityId(UUID pi, String elementId) {
        return tx.execute(s -> activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getBpmnElementId().equals(elementId)
                && (a.getStatus() == ActivityStatus.CREATED || a.getStatus() == ActivityStatus.IN_PROGRESS))
            .map(ActivityEntity::getId)
            .findFirst().orElseThrow());
    }

    private long countByStatus(UUID pi, ActivityStatus... statuses) {
        List<ActivityStatus> wanted = List.of(statuses);
        return tx.execute(s -> activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> wanted.contains(a.getStatus()))
            .count());
    }

    private long activityCount(UUID pi, String elementId) {
        return tx.execute(s -> activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getBpmnElementId().equals(elementId))
            .count());
    }

    private long openIncidentCount(UUID pi) {
        IncidentQuery q = new IncidentQuery();
        q.setProcessInstanceId(pi);
        q.setResolved(false);
        return tx.execute(s -> (long) queryService.findIncidents(q, null).getData().size());
    }

    private long totalIncidentCount(UUID pi) {
        // Только инциденты СВОЕГО инстанса (activityId → processInstanceId):
        // H2-схема общая, соседние тесты пишут свои строки.
        return tx.execute(s -> {
            java.util.Map<UUID, ActivityEntity> byId = new java.util.HashMap<>();
            for (ActivityEntity a : activityRepository.findAll()) {
                byId.put(a.getId(), a);
            }
            return incidentRepository.findAll().stream()
                .map(i -> byId.get(i.getActivityId()))
                .filter(a -> a != null && pi.equals(a.getProcessInstanceId()))
                .count();
        });
    }

    /** Та же последовательность, что операторская отмена REST (без authz/аудита). */
    private void operatorCancelSequence(UUID pi) {
        tx.executeWithoutResult(s -> {
            dbService.cancelActiveActivities(pi);
            dbService.deleteTimerJobsByProcessInstanceId(pi);
            dbService.deleteMessageSubscriptionsByProcessInstanceId(pi);
            dbService.cancelProcessInstance(pi);
        });
    }

    /** G-P: после финализации инстанса — ни одной живой/ошибочной активности и мусора вокруг. */
    private void assertNoLeftovers(UUID pi) {
        assertThat(countByStatus(pi, ActivityStatus.CREATED, ActivityStatus.IN_PROGRESS, ActivityStatus.ERROR))
            .as("нет живых и ERROR-активностей").isZero();
        assertThat(openIncidentCount(pi)).as("нет открытых инцидентов").isZero();
        long timerLeftovers = tx.execute(s -> timerJobRepository.findAll().stream()
            .filter(j -> pi.equals(j.getProcessInstanceId())).count());
        assertThat(timerLeftovers).as("нет висящих timer jobs").isZero();
        long msgLeftovers = tx.execute(s -> messageSubscriptionRepository.findAll().stream()
            .filter(m -> pi.equals(m.getProcessInstanceId())).count());
        assertThat(msgLeftovers).as("нет висящих message-подписок").isZero();
        long sigLeftovers = tx.execute(s -> signalSubscriptionRepository.findAll().stream()
            .filter(m -> pi.equals(m.getProcessInstanceId())).count());
        assertThat(sigLeftovers).as("нет висящих signal-подписок").isZero();
    }

    // ── п.1а: отмена инстанса с открытым инцидентом ──

    @Test
    void cancelWithOpenIncident_closesIncidentAndErrorActivity() throws Exception {
        UUID pi = deployAndStart("test-service-task-fail.bpmn");
        UUID svc = activeActivityId(pi, "svc");
        tx.executeWithoutResult(s -> runtimeService.failServiceTask(svc, "boom", 0));
        assertThat(openIncidentCount(pi)).isEqualTo(1);
        assertThat(countByStatus(pi, ActivityStatus.ERROR)).isEqualTo(1);

        operatorCancelSequence(pi);

        boolean cancelled = tx.execute(s -> processInstanceRepository.findById(pi).orElseThrow().isCancelled());
        assertThat(cancelled).isTrue();
        assertThat(openIncidentCount(pi)).as("открытый инцидент закрыт отменой").isZero();
        assertThat(countByStatus(pi, ActivityStatus.ERROR)).as("ERROR переведена в CANCELLED").isZero();
        assertNoLeftovers(pi);
    }

    // ── п.1б: terminate-выход при открытом инциденте ──

    @Test
    void terminateWithOpenIncident_closesLeftovers() throws Exception {
        UUID pi = deployAndStart("test-c849-terminate-with-open-incident.bpmn");
        UUID svc = activeActivityId(pi, "svc");
        UUID taskB = activeActivityId(pi, "taskB");
        tx.executeWithoutResult(s -> runtimeService.failServiceTask(svc, "boom", 0));
        assertThat(openIncidentCount(pi)).isEqualTo(1);

        tx.executeWithoutResult(s -> runtimeService.completeUserTask(taskB, List.of()));

        Object completedAt = tx.execute(s -> dbService.getProcessInstance(pi).getCompletedAt());
        assertThat(completedAt).isNotNull();
        assertThat(openIncidentCount(pi)).as("открытый инцидент закрыт terminate-выходом").isZero();
        assertThat(countByStatus(pi, ActivityStatus.ERROR)).as("ERROR переведена в CANCELLED").isZero();
        assertNoLeftovers(pi);
    }

    // ── п.1в: resolve на закрытом инстансе (legacy-строки: инцидент открыт, инстанс закрыт) ──

    @Test
    void resolveOnFinishedInstance_conflictsWithoutReexecution() throws Exception {
        UUID pi = deployAndStart("test-service-task-fail.bpmn");
        UUID svc = activeActivityId(pi, "svc");
        tx.executeWithoutResult(s -> runtimeService.failServiceTask(svc, "boom", 0));
        UUID incidentId = tx.execute(
            s -> queryService.findIncidents(openQuery(pi), null).getData().get(0).getId());
        // Legacy-состояние прод-данных (70 строк): инстанс закрыт БЕЗ финализации —
        // напрямую через репозиторий, в обход точки финализации (именно так эти строки и возникли).
        tx.executeWithoutResult(s -> {
            processInstanceRepository.setCancelled(pi, true);
            processInstanceRepository.setCompletedAt(pi, Instant.now());
        });

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> runtimeService.resolveIncident(incidentId, List.of())))
            .as("resolve на закрытом инстансе — отказ, а не перезапуск")
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT));

        assertThat(activityCount(pi, "svc")).as("новых активностей нет").isEqualTo(1);
        assertThat(totalIncidentCount(pi)).as("новых инцидентов нет").isEqualTo(1);
        assertThat(openIncidentCount(pi)).as("инцидент остался открыт (legacy)").isEqualTo(1);
    }

    private IncidentQuery openQuery(UUID pi) {
        IncidentQuery q = new IncidentQuery();
        q.setProcessInstanceId(pi);
        q.setResolved(false);
        return q;
    }

    // ── п.1г: retry/manual-complete на закрытом инстансе — no-op ──

    @Test
    void failOnCancelledInstance_isIgnored() throws Exception {
        UUID pi = deployAndStart("test-service-task-fail.bpmn");
        UUID svc = activeActivityId(pi, "svc");
        tx.executeWithoutResult(s -> runtimeService.failServiceTask(svc, "boom", 0));
        assertThat(totalIncidentCount(pi)).isEqualTo(1);
        operatorCancelSequence(pi);

        tx.executeWithoutResult(s -> runtimeService.failServiceTask(svc, "again", 0));

        assertThat(totalIncidentCount(pi)).as("повторный fail не создаёт инцидент").isEqualTo(1);
        assertThat(openIncidentCount(pi)).isZero();
        assertThat(activityCount(pi, "svc")).as("новых активностей нет").isEqualTo(1);
    }

    @Test
    void completeOnCancelledInstance_finishesNothing() throws Exception {
        // WO-C8-49 (п.1г, user-task половина): complete по задаче закрытого
        // инстанса НЕ продолжает поток. Статус-guard отсекает CANCELLED-строку
        // (replay=false — это не «поглощённый повтор», а тихий игнор stale);
        // поток не продолжается: новых активностей нет, хвостов нет.
        UUID pi = deployAndStart("test-c849-terminate-with-open-incident.bpmn");
        UUID taskB = activeActivityId(pi, "taskB");
        operatorCancelSequence(pi);

        boolean replay = Boolean.TRUE.equals(tx.execute(s -> runtimeService.completeUserTask(taskB, List.of())));

        assertThat(replay).as("не повтор (replay=false), а тихий игнор stale-статуса").isFalse();
        assertThat(activityCount(pi, "taskB")).as("новых активностей нет").isEqualTo(1);
        assertNoLeftovers(pi);
    }
}
