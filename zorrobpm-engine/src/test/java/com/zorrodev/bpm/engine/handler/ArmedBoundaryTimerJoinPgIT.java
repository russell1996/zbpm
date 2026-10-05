package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.dto.IdDTO;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.scheduler.TimerBatchProcessor;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-35 (CR-09, ШАГ C) — BLOCKER-1 red-team на ЖИВОМ выстреле, а не на форсированном.
 *
 * <p>Что закрывает этот класс. H2-тест {@code InclusiveJoinReadinessIntegrationTests.
 * armedBoundaryTimer_joinWaitsForItAndThenPassesThroughExactlyOnce} доказывает правило «взведённый
 * триггер = ещё может доставить» тем, что граница НЕ стреляет: последний доставщик умирает
 * завершением {@code taskWait}. Red-team (BLOCKER-1) воспроизводил обратную половину — «граница
 * ВЫСТРЕЛИЛА, и join прошёл ВТОРЫМ разом, {@code taskNotify} создан дважды» — но живого выстрела в
 * ветке не было: стенд verifier'а форсировал {@code fire} без {@code claim}, чего прод не делает.
 *
 * <p>Здесь выстрел настоящий и боевой: {@code TimerBatchProcessor.processBatch()} → выборка
 * {@code findDueLocked} (FOR UPDATE SKIP LOCKED) → {@code TimerJobExecutor.fire} →
 * {@code claimTimerJob} (UPDATE ... fired = true) → {@code fireBoundaryTimer}. Доказательство
 * claim'а — сама строка в БД ({@code fired = true} после батча), а не только счётчик ниже по потоку.
 *
 * <p>Почему PostgreSQL обязателен: выстрел идёт через {@code REQUIRES_NEW}-транзакции
 * ({@code TimerJobExecutor.fire}) и реальные блокировки строк — на H2 этот путь не воспроизводится
 * (см. {@code pg-vs-h2-divergence}; тот же приём в {@code RepeatingBoundaryTimerPgIT}).
 */
public class ArmedBoundaryTimerJoinPgIT extends PostgresIT {

    @Autowired ProcessDefinitionService processDefinitionService;
    @Autowired RuntimeService runtimeService;
    @Autowired QueryService queryService;
    @Autowired ActivityRepository activityRepository;
    @Autowired TimerBatchProcessor timerBatchProcessor;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    /** Park the background poller so it cannot race the explicit processBatch() calls below. */
    @DynamicPropertySource
    static void parkBackgroundPoller(DynamicPropertyRegistry registry) {
        registry.add("zorrobpm.engine.timer-poll-interval-ms", () -> "3600000");
    }

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        jdbc.execute("DELETE FROM timer_start_jobs");
        jdbc.execute("DELETE FROM timer_jobs");
        jdbc.execute("DELETE FROM message_subscriptions");
        jdbc.execute("DELETE FROM signal_subscriptions");
        jdbc.execute("DELETE FROM parallel_gateways");
        jdbc.execute("DELETE FROM incidents");
        jdbc.execute("DELETE FROM user_tasks");
        jdbc.execute("DELETE FROM service_tasks");
        jdbc.execute("DELETE FROM variables");
        jdbc.execute("DELETE FROM activities");
        jdbc.execute("DELETE FROM tokens");
        jdbc.execute("DELETE FROM events");
        jdbc.execute("DELETE FROM process_instances");
        jdbc.execute("DELETE FROM process_definitions WHERE code LIKE 'c835-%'");
    }

    private List<ActivityEntity> activities(UUID pi) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .toList();
    }

    private long countOf(UUID pi, String elementId, ActivityStatus status) {
        return activities(pi).stream()
            .filter(a -> a.getBpmnElementId().equals(elementId))
            .filter(a -> a.getStatus() == status)
            .count();
    }

    /**
     * BLOCKER-1, полная дуга на живом выстреле: armed boundary timer ДОСТАВЛЯЕТ вторую ветвь в
     * паркованный join — и join проходит РОВНО ОДИН раз.
     *
     * <p>Диаграмма {@code test-c835-boundary-incl-join.bpmn}: {@code pfork → {taskMain → join,
     * taskWait → endWait}}, непрерывающая граница {@code tmrCheck} (PT10H) на {@code taskWait} с
     * исходящим {@code gB → join}. Завершение {@code taskMain} приводит ветвь в join и паркует её:
     * у взведённой границы нет строки activity, поэтому правило, построенное на живых activity,
     * выстрелило бы join уже здесь (это и есть BLOCKER-1), а второе срабатывание пришло бы от
     * границы — {@code taskNotify} создался бы ДВАЖДЫ, молча, без инцидента.
     */
    @Test
    void armedBoundaryTimerFiresForReal_theJoinPassesThroughExactlyOnce() throws Exception {
        String key = "c835-armed-boundary-" + UUID.randomUUID().toString().substring(0, 8);
        String bpmn = Files.readString(Paths.get("src/test/files/test-c835-boundary-incl-join.bpmn"))
            .replace("test-c835-boundary-incl-join", key);
        UUID defId = processDefinitionService.addProcessDefinition(bpmn).getId();

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(defId);
        UUID pi = runtimeService.startProcessInstance(dto).getId();

        assertThat(countOf(pi, "taskNotify", ActivityStatus.CREATED)).isZero();

        // ── step 1: one branch arrives, the join parks ──────────────────────────────────────
        UUID taskMainId = activities(pi).stream()
            .filter(a -> a.getBpmnElementId().equals("taskMain") && a.getStatus() == ActivityStatus.CREATED)
            .findFirst().orElseThrow().getId();
        runtimeService.completeUserTask(taskMainId, List.of());

        assertThat(countOf(pi, "join", ActivityStatus.COMPLETED))
            .as("the boundary is ARMED and can still deliver a branch — the join must park")
            .isZero();
        assertThat(countOf(pi, "taskNotify", ActivityStatus.CREATED))
            .as("nothing downstream of the join may run before the boundary delivers")
            .isZero();

        // The armed boundary is a real pending timer job, and it is NOT consumed by the arrival.
        UUID jobId = jdbc.queryForObject(
            "SELECT id FROM timer_jobs WHERE process_instance_id = ? AND boundary_element_id = 'tmrCheck' AND fired = false",
            UUID.class, pi);
        assertThat(jobId).as("the boundary timer must be armed while taskWait is live").isNotNull();

        // ── step 2: the boundary REALLY fires, through claim ────────────────────────────────
        // PT10H is not testable in wall-clock time; make the job due, then let the production
        // batch/claim path do the rest. Nothing here calls fire() directly.
        jdbc.update("UPDATE timer_jobs SET due_at = ? WHERE id = ?",
            Timestamp.from(Instant.now().minusSeconds(60)), jobId);
        timerBatchProcessor.processBatch();

        assertThat(jdbc.queryForObject("SELECT fired FROM timer_jobs WHERE id = ?", Boolean.class, jobId))
            .as("TimerJobExecutor.fire must CLAIM the job (UPDATE ... fired = true) — this is the "
                + "difference between the production path and the verifier's forced stand-in")
            .isTrue();

        // ── step 3: the join passes through exactly ONCE ───────────────────────────────────
        assertThat(countOf(pi, "join", ActivityStatus.COMPLETED))
            .as("the armed boundary delivered the second branch — the join must fire now")
            .isEqualTo(1L);
        assertThat(countOf(pi, "taskNotify", ActivityStatus.CREATED))
            .as("taskNotify created twice IS the red-team BLOCKER-1 observation (double side effect)")
            .isEqualTo(1L);

        // ── step 4: a second batch must not fire the boundary again (claim is the guard) ────
        timerBatchProcessor.processBatch();
        assertThat(countOf(pi, "join", ActivityStatus.COMPLETED))
            .as("the claimed job is not re-selected, so the join cannot pass through a second time")
            .isEqualTo(1L);
        assertThat(countOf(pi, "taskNotify", ActivityStatus.CREATED)).isEqualTo(1L);
        assertThat(queryService.getProcessInstance(pi).getCompletedAt()).isNull();

        // The tail is reachable: the join really continued, it did not just park again. taskWait is
        // STILL live here (the boundary was non-interrupting), so the instance is legitimately
        // RUNNING at this point — completing only taskNotify must NOT complete it.
        tx.executeWithoutResult(status -> runtimeService.completeUserTask(
            activityRepository.findAll().stream()
                .filter(a -> a.getProcessInstanceId().equals(pi))
                .filter(a -> "taskNotify".equals(a.getBpmnElementId()))
                .filter(a -> a.getStatus() == ActivityStatus.CREATED)
                .findFirst().orElseThrow().getId(),
            List.of()));
        assertThat(queryService.getProcessInstance(pi).getCompletedAt())
            .as("a non-interrupting boundary leaves the host branch alive, so the instance must "
                + "still be RUNNING — completing only the join's tail must not complete it")
            .isNull();

        tx.executeWithoutResult(status -> runtimeService.completeUserTask(
            activityRepository.findAll().stream()
                .filter(a -> a.getProcessInstanceId().equals(pi))
                .filter(a -> "taskWait".equals(a.getBpmnElementId()))
                .filter(a -> a.getStatus() == ActivityStatus.CREATED)
                .findFirst().orElseThrow().getId(),
            List.of()));
        assertThat(queryService.getProcessInstance(pi).getCompletedAt())
            .as("with both branches done the instance completes — no parked token left behind")
            .isNotNull();
    }
}