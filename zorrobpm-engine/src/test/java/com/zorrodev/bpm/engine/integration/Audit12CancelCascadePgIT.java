package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-AUDIT-12 (F-02) на живом PostgreSQL: отмена родителя с живым дочерним
 * инстансом call activity.
 *
 * <p>Сценарий аудита ({@code docs/audit/ZorroBPM-full-audit-2026-10-19.md}, F-02):
 * REST-cancel не каскадирует отмену на дочерние инстансы call activity, поэтому
 * ребёнок остаётся жить; позже он завершается и (а) переписывает историю
 * отменённого инстанса (CANCELLED → COMPLETED), (б) продолжает поток отменённого
 * процесса (новые activity-строки в завершённом инстансе), (в) шлёт второй
 * {@code processCompleted} и уводит gauge активных инстансов в минус.
 *
 * <p>Путь отмены — боевой: тот же порядок, что делает REST-операторская отмена
 * ({@code ProcessInstanceRuntimeOperationsImpl#cancelProcessInstance}:
 * {@code cancelActiveActivities} → удаление таймеров/подписок →
 * {@code DBService.cancelProcessInstance} — единый choke-point отмены, через
 * который проходят и REST, и внутренние пути (deferred canceling-хвост)).
 *
 * <p>Инвариант утечек (G-P) проверяется и здесь (явно), и глобальным
 * {@code LeakInvariantExtension} (WO-OPS-26) — прогон с
 * {@code -Dzbpm.leakInvariant=enforce} обязан быть зелёным.
 */
@Tag("pg")
class Audit12CancelCascadePgIT extends PostgresIT {

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private DBService dbService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private ProcessInstanceRepository processInstanceRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MeterRegistry meterRegistry;

    private double counter(String name) {
        return meterRegistry.find(name).counter().count();
    }

    private double gauge(String name) {
        return meterRegistry.find(name).gauge().value();
    }

    private UUID deployParent() throws Exception {
        processDefinitionService.addProcessDefinition(
            Files.readString(Paths.get("src/test/files/test-audit12-call-child.bpmn")));
        ProcessDefinition parent = processDefinitionService.addProcessDefinition(
            Files.readString(Paths.get("src/test/files/test-audit12-call-parent.bpmn")));
        return parent.getId();
    }

    private UUID start(UUID pdId) {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        dto.setVariables(List.of());
        return runtimeService.startProcessInstance(dto).getId();
    }

    /** Операторская отмена: тот же порядок, что в REST-операции отмены инстанса. */
    private void operatorCancel(UUID processInstanceId) {
        dbService.cancelActiveActivities(processInstanceId);
        dbService.deleteTimerJobsByProcessInstanceId(processInstanceId);
        dbService.deleteMessageSubscriptionsByProcessInstanceId(processInstanceId);
        dbService.cancelProcessInstance(processInstanceId);
    }

    private UUID childOf(UUID parentInstanceId) {
        List<com.zorrodev.bpm.engine.entity.ProcessInstanceEntity> children =
            processInstanceRepository.findAll(
                ProcessInstanceRepository.byParentProcessInstanceId(parentInstanceId));
        assertThat(children).as("one child instance per call activity").hasSize(1);
        return children.get(0).getId();
    }

    private List<ActivityEntity> activitiesOf(UUID instanceId) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(instanceId))
            .toList();
    }

    /**
     * F-02 (a): отмена родителя УБИВАЕТ дочерний инстанс call activity.
     *
     * <p>RED на коде до фикса: ребёнок остаётся RUNNING (completed_at IS NULL,
     * cancelled = false), его user task — CREATED. Именно этот «живой ребёнок» —
     * корень F-02: он потом завершается и ломает историю отменённого родителя.
     */
    @Test
    @Transactional
    void cancelParent_cascadesToRunningChildInstance() throws Exception {
        UUID parent = start(deployParent());
        UUID child = childOf(parent);

        assertThat(dbService.getProcessInstance(child).getCompletedAt())
            .as("child is running before the cancel").isNull();
        assertThat(activitiesOf(child)).as("child has its live user task")
            .anyMatch(a -> "childWait".equals(a.getBpmnElementId())
                && a.getStatus() == ActivityStatus.CREATED);

        operatorCancel(parent);

        var childAfter = dbService.getProcessInstance(child);
        assertThat(childAfter.getCompletedAt())
            .as("child instance must be terminal after the parent's cancel").isNotNull();
        assertThat(childAfter.isCancelled())
            .as("child instance must be cancelled, not left running").isTrue();
        assertThat(activitiesOf(child))
            .as("no live activity left in the cancelled child")
            .noneMatch(a -> a.getStatus() == ActivityStatus.CREATED
                || a.getStatus() == ActivityStatus.IN_PROGRESS);
    }

    /**
     * F-02 (b): позднее завершение ребёнка НЕ переписывает историю отменённого
     * родителя и НЕ продолжает его поток.
     *
     * <p>RED на коде до фикса: ребёнок (не убитый каскадом) завершается →
     * {@code completeActivity} родительской call-activity строки перезаписывает
     * CANCELLED на COMPLETED, а {@code proceedToOutgoing} создаёт в завершённом
     * отменённом инстансе живую activity-строку (родительский user task) —
     * это и есть «продолжение потока отменённого процесса» (G-P: утечка).
     */
    @Test
    @Transactional
    void lateChildCompletion_afterParentCancel_doesNotRewriteHistoryOrContinueParent() throws Exception {
        UUID parent = start(deployParent());
        UUID child = childOf(parent);
        UUID childTask = activitiesOf(child).stream()
            .filter(a -> "childWait".equals(a.getBpmnElementId()))
            .map(ActivityEntity::getId)
            .findFirst().orElseThrow();

        operatorCancel(parent);

        UUID parentCallActivity = activitiesOf(parent).stream()
            .filter(a -> "call".equals(a.getBpmnElementId()))
            .map(ActivityEntity::getId)
            .findFirst().orElseThrow();
        assertThat(activityRepository.findById(parentCallActivity).orElseThrow().getStatus())
            .as("parent call activity is cancelled").isEqualTo(ActivityStatus.CANCELLED);

        double completedBefore = counter("zbpm.process.completed");
        double activeBefore = gauge("zbpm.process.instances.active");

        // Позднее завершение ребёнка (часы/дни спустя в проде).
        runtimeService.completeUserTask(childTask, List.of());

        assertThat(activityRepository.findById(parentCallActivity).orElseThrow().getStatus())
            .as("cancelled parent call activity must stay CANCELLED (history is not rewritten)")
            .isEqualTo(ActivityStatus.CANCELLED);
        assertThat(activitiesOf(parent))
            .as("cancelled parent must not continue its flow (no live activity)")
            .noneMatch(a -> a.getStatus() == ActivityStatus.CREATED
                || a.getStatus() == ActivityStatus.IN_PROGRESS);
        assertThat(dbService.getProcessInstance(parent).isCancelled())
            .as("parent stays cancelled").isTrue();
        assertThat(counter("zbpm.process.completed") - completedBefore)
            .as("no second processCompleted from the cancelled parent's flow").isEqualTo(0.0);
        assertThat(gauge("zbpm.process.instances.active"))
            .as("active-instances gauge must not go negative / stay inflated")
            .isEqualTo(activeBefore);
    }

    /**
     * F-02 (b) + метрики: gauge активных инстансов возвращается к базовой
     * линии сразу после отмены (оба инстанса терминальны), а счётчик
     * {@code process.completed} не растёт (отмена — не завершение).
     */
    @Test
    @Transactional
    void cancelParent_keepsActiveInstanceGaugeBalancedAndDoesNotCountCompletion() throws Exception {
        double activeBefore = gauge("zbpm.process.instances.active");
        double completedBefore = counter("zbpm.process.completed");

        UUID parent = start(deployParent());
        childOf(parent);
        assertThat(gauge("zbpm.process.instances.active"))
            .as("parent + child are both active").isEqualTo(activeBefore + 2.0);

        operatorCancel(parent);

        assertThat(gauge("zbpm.process.instances.active"))
            .as("both instances terminal after the cancel — gauge balanced")
            .isEqualTo(activeBefore);
        assertThat(counter("zbpm.process.completed") - completedBefore)
            .as("cancel is not a completion").isEqualTo(0.0);
    }

    /**
     * F-02 (b), второй слой: переход активности в COMPLETED — условный.
     *
     * <p>Позднее завершение отменённой активности (тот же путь, что и
     * «ребёнок завершился после отмены родителя») НЕ переписывает её статус:
     * безусловный UPDATE в {@code ActivityRepository.setStatusAndCompletedAt}
     * переворачивал CANCELLED в COMPLETED — искажённая история отменённого
     * инстанса. RED без фикса: строка становится COMPLETED и шлётся
     * activity.completed.
     */
    @Test
    @Transactional
    void lateCompletion_ofCancelledActivity_doesNotRewriteHistory() throws Exception {
        UUID parent = start(deployParent());
        UUID child = childOf(parent);
        UUID childTask = activitiesOf(child).stream()
            .filter(a -> "childWait".equals(a.getBpmnElementId()))
            .map(ActivityEntity::getId)
            .findFirst().orElseThrow();

        operatorCancel(parent);
        assertThat(activityRepository.findById(childTask).orElseThrow().getStatus())
            .as("the child's task is cancelled by the cascade").isEqualTo(ActivityStatus.CANCELLED);

        long eventsBefore = jdbc.queryForObject(
            "SELECT COUNT(*) FROM events WHERE process_instance_id = ? AND type = 'activity.completed'",
            Long.class, child);

        // Позднее «завершение» уже отменённой активности — прод-путь
        // ActivityDbOperationsImpl.completeActivity.
        dbService.completeActivity(childTask);

        assertThat(activityRepository.findById(childTask).orElseThrow().getStatus())
            .as("cancelled activity must stay CANCELLED (history is not rewritten)")
            .isEqualTo(ActivityStatus.CANCELLED);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM events WHERE process_instance_id = ? AND type = 'activity.completed'",
            Long.class, child))
            .as("no activity.completed event for a refused completion")
            .isEqualTo(eventsBefore);
    }

    /**
     * G-P: после отмены в обоих инстансах нет мусора — живых activity, открытых
     * задач, таймеров, подписок, инцидентов и токенов с несведённым счётчиком.
     */
    @Test
    @Transactional
    void cancelParent_leavesNoLiveResidueInEitherInstance() throws Exception {
        UUID parent = start(deployParent());
        UUID child = childOf(parent);

        operatorCancel(parent);

        for (UUID instance : List.of(parent, child)) {
            assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM activities WHERE process_instance_id = ?"
                    + " AND status IN ('CREATED','IN_PROGRESS')", Long.class, instance))
                .as("no live activity in " + instance).isZero();
            assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_tasks t WHERE t.process_instance_id = ?"
                    + " AND t.completed_at IS NULL AND EXISTS ("
                    + "  SELECT 1 FROM activities a WHERE a.process_instance_id = t.process_instance_id"
                    + "   AND a.status IN ('CREATED','IN_PROGRESS'))",
                Long.class, instance))
                .as("no open user task on a live activity in " + instance).isZero();
            assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM timer_jobs WHERE process_instance_id = ?", Long.class, instance))
                .as("no timer job in " + instance).isZero();
            assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM message_subscriptions WHERE process_instance_id = ?",
                Long.class, instance))
                .as("no message subscription in " + instance).isZero();
            assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM incidents i JOIN activities a ON a.id = i.activity_id"
                    + " WHERE a.process_instance_id = ? AND i.completed_at IS NULL",
                Long.class, instance))
                .as("no open incident in " + instance).isZero();
            assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM tokens t JOIN activities a ON a.token = t.id"
                    + " WHERE a.process_instance_id = ? AND t.pending_branches > 0",
                Long.class, instance))
                .as("no token with an unreduced branch counter in " + instance).isZero();
        }
    }
}
