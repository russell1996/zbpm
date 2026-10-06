package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.dto.TimerJob;
import com.zorrodev.bpm.engine.scheduler.TimerJobExecutor;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REL-63 (NEW5-02): оставшиеся пути, которые бьются с отменой экземпляра.
 *
 * <p>WO-REL-59 перевёл на единый порядок захвата instance→activity только
 * complete-пути ({@code completeUserTask}/{@code completeServiceTask}). Остальные
 * пути начинали с activity-lock ({@code ElementSupport.lockAndReload} —
 * {@code SELECT ... FOR UPDATE OF} лочит ТОЛЬКО строку activity, см.
 * {@code Rel59SqlProbePgIT}), а отмена берёт instance-lock первым
 * ({@code ProcessInstanceRuntimeOperationsImpl.cancelProcessInstance} →
 * {@code lockProcessInstance}, затем {@code cancelActiveActivities} →
 * построчный UPDATE activity-строк).
 *
 * <p>Цикл замыкается не на явном вызове, а на неявном: продолжение пути в конце
 * flow делает {@code completeProcessInstance} — UPDATE строки
 * {@code process_instances}, то есть просит ТОТ ЖЕ instance-row-lock, который уже
 * держит отмена. Итого до фикса: путь держит activity-lock и хочет instance-lock;
 * отмена держит instance-lock и хочет activity-lock — классический ABBA, проигравший
 * получает {@code ERROR: deadlock detected} (SQLState 40P01) через
 * {@code deadlock_timeout} (~1с).
 *
 * <p>V6: два реальных потока, две реальные транзакции, {@code CyclicBarrier} на
 * старте. Окно гонки держит sleep отмены между её двумя захватами — так обе
 * стороны успевают взять ПЕРВЫЙ лок до того, как кто-то попросит второй (тот же
 * приём, что в {@code Rel59CompleteCancelDeadlockPgIT}, скелет потоков A/B не
 * изобретаем заново).
 *
 * <p>G-N / P-67: сторона «путь» зовёт НАСТОЯЩИЙ прод-метод целиком
 * ({@code TimerJobExecutor.fire} — ровно то, что гоняет поллер, и внутри него
 * {@code ActivityService.signal}/{@code fireBoundaryTimer}; и
 * {@code ActivityService.correlateMessage} — ровно то, что зовёт publish). Порядок
 * захватов живёт ВНУТРИ прод-кода, поэтому мутация его тела (возврат
 * activity-first) валит этот тест без единой правки теста. Проверено POF: на
 * дереве до фикса все три сценария дают {@code deadlock detected} (см. отчёт).
 *
 * <p>Реальный PostgreSQL обязателен: H2 не воспроизводит row-level блокировки и
 * {@code deadlock_timeout} — на H2 тест был бы зелёным и на сломанном коде.
 *
 * <p>Отмена представлена lock-скелетом ({@code lockProcessInstance} → sleep →
 * {@code cancelActiveActivities}), а не вызовом REST-метода: он живёт в модуле
 * {@code zorrobpm-rest}, а этот тест — в {@code zorrobpm-engine} (cross-модульный
 * тест без нужды). Скелет побайтово тот же, что у REL-59, и содержит РОВНО те два
 * захвата, которые образуют цикл; остальные шаги отмены (удаление таймеров/подписок,
 * {@code cancelProcessInstance}) на порядок захватов не влияют.
 */
public class Rel63RemainingAbbaDeadlockPgIT extends PostgresIT {

    private static final int ROUNDS = 5;
    private static final String KEY_PREFIX = "rel63-";

    /**
     * This class drives the firing paths explicitly. Park the background poller
     * (1 h instead of 5 s) so it cannot grab the due test job and make the race
     * non-deterministic — same reasoning as {@code TimerBatchIsolationPgIT}.
     */
    @DynamicPropertySource
    static void parkBackgroundPoller(DynamicPropertyRegistry registry) {
        registry.add("zorrobpm.engine.timer-poll-interval-ms", () -> "3600000");
    }

    @Autowired ProcessDefinitionService processDefinitionService;
    @Autowired RuntimeService runtimeService;
    @Autowired ActivityService activityService;
    @Autowired TimerJobExecutor timerJobExecutor;
    @Autowired DBService dbService;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;

    private final List<UUID> createdInstances = new CopyOnWriteArrayList<>();
    private final List<UUID> createdDefinitions = new CopyOnWriteArrayList<>();

    @AfterEach
    void purgeOwnRows() {
        // Scoped by the ids this class created — never a blanket table wipe: the
        // engine PG suite shares one database with foreign classes (P-8).
        for (UUID piId : createdInstances) {
            purgeInstance(piId);
        }
        for (UUID pdId : createdDefinitions) {
            jdbc.update("DELETE FROM process_definitions WHERE id = ?", pdId);
        }
        createdInstances.clear();
        createdDefinitions.clear();
    }

    /** Children before parents (no ON DELETE CASCADE in the schema). */
    private void purgeInstance(UUID piId) {
        jdbc.update("DELETE FROM events WHERE process_instance_id = ?", piId);
        jdbc.update("DELETE FROM variables WHERE process_instance_id = ?", piId);
        // incidents держит только activity_id (сверено с живой схемой psql \d incidents)
        jdbc.update("DELETE FROM incidents WHERE activity_id IN "
            + "(SELECT id FROM activities WHERE process_instance_id = ?)", piId);
        jdbc.update("DELETE FROM timer_jobs WHERE process_instance_id = ?", piId);
        jdbc.update("DELETE FROM message_subscriptions WHERE process_instance_id = ?", piId);
        // WO-IN-3: кандидаты — ДЕТИ user_tasks, FK RESTRICT → дети раньше родителя.
        jdbc.update("DELETE FROM user_task_candidates WHERE user_task_id IN "
            + "(SELECT id FROM user_tasks WHERE process_instance_id = ?)", piId);
        jdbc.update("DELETE FROM user_tasks WHERE process_instance_id = ?", piId);
        jdbc.update("DELETE FROM service_tasks WHERE process_instance_id = ?", piId);
        List<UUID> tokens = jdbc.queryForList(
            "SELECT DISTINCT token FROM activities WHERE process_instance_id = ?", UUID.class, piId);
        jdbc.update("DELETE FROM activities WHERE process_instance_id = ?", piId);
        for (UUID tokenId : tokens) {
            // only tokens no surviving activity references
            jdbc.update("DELETE FROM tokens WHERE id = ? AND NOT EXISTS "
                + "(SELECT 1 FROM activities WHERE token = ?)", tokenId, tokenId);
        }
        jdbc.update("DELETE FROM process_instances WHERE id = ?", piId);
    }

    // ── criterion 2: timer × cancel ────────────────────────────────────────────

    /**
     * Criterion 2: промежуточный таймер (событие {@code timer1}) против отмены.
     * Прод-путь — {@code TimerJobExecutor.fire}, ровно то, что вызывает поллер.
     */
    @Test
    void timerFireVsCancel_noDeadlockFiveRounds() throws Exception {
        UUID defId = deploy("test-timer.bpmn");
        List<String> deadlocks = new CopyOnWriteArrayList<>();
        for (int round = 0; round < ROUNDS; round++) {
            UUID piId = startInstance(defId);
            TimerJob job = dueTimerJob(piId);
            assertThat(job.getBoundaryElementId())
                .as("промежуточный таймер, не boundary")
                .isNull();
            race("timer", round, deadlocks, piId, () -> timerJobExecutor.fire(job));
        }
        assertThat(deadlocks).as("0 deadlock за " + ROUNDS + " раундов (таймер × отмена)").isEmpty();
    }

    // ── criterion 3a: message × cancel ─────────────────────────────────────────

    /**
     * Criterion 3: корреляция сообщения против отмены. Прод-путь —
     * {@code ActivityService.correlateMessage} (он же дёргается publish-эндпоинтом),
     * внутри — {@code CompletionService.signal}.
     */
    @Test
    void messageCorrelationVsCancel_noDeadlockFiveRounds() throws Exception {
        UUID defId = deploy("test-message.bpmn");
        List<String> deadlocks = new CopyOnWriteArrayList<>();
        for (int round = 0; round < ROUNDS; round++) {
            UUID piId = startInstance(defId);
            Integer subs = jdbc.queryForObject(
                "SELECT count(*) FROM message_subscriptions WHERE process_instance_id = ?",
                Integer.class, piId);
            assertThat(subs).as("подписка order-approved создана").isEqualTo(1);
            race("message", round, deadlocks, piId,
                () -> activityService.correlateMessage("order-approved", piId, List.of()));
        }
        assertThat(deadlocks).as("0 deadlock за " + ROUNDS + " раундов (сообщение × отмена)").isEmpty();
    }

    // ── criterion 3b: boundary × cancel ───────────────────────────────────────

    /**
     * Criterion 3: граничное событие против отмены. Прод-путь —
     * {@code TimerJobExecutor.fire} для boundary-job, внутри
     * {@code ActivityService.fireBoundaryTimer} → {@code EventTrigger.fireBoundary}.
     */
    @Test
    void boundaryTimerVsCancel_noDeadlockFiveRounds() throws Exception {
        UUID defId = deploy("test-boundary.bpmn");
        List<String> deadlocks = new CopyOnWriteArrayList<>();
        for (int round = 0; round < ROUNDS; round++) {
            UUID piId = startInstance(defId);
            TimerJob job = dueTimerJob(piId);
            assertThat(job.getBoundaryElementId())
                .as("job граничного таймера")
                .isEqualTo("boundary1");
            race("boundary", round, deadlocks, piId, () -> timerJobExecutor.fire(job));
        }
        assertThat(deadlocks).as("0 deadlock за " + ROUNDS + " раундов (граничное событие × отмена)").isEmpty();
    }

    // ── the race itself (skeleton A/B as in Rel59CompleteCancelDeadlockPgIT) ──

    /**
     * Thread A — the production path, in its own transaction (mirrors
     * {@code TimerJobExecutor.fire} REQUIRES_NEW / the class-level
     * {@code @Transactional} of {@code ActivityServiceImpl}).
     *
     * <p>Thread B — cancel: instance-lock → sleep (holds the ABBA window open) →
     * row-locking UPDATE of the active activities.
     */
    private void race(String label, int round, List<String> deadlocks, UUID piId, Runnable prodPath)
            throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> prod = pool.submit(() ->
                new TransactionTemplate(txManager).execute(status -> {
                    awaitQuietly(start);
                    prodPath.run();
                    return null;
                }));
            Future<?> cancel = pool.submit(() ->
                new TransactionTemplate(txManager).execute(status -> {
                    awaitQuietly(start);
                    dbService.lockProcessInstance(piId);
                    sleepQuietly(500);
                    dbService.cancelActiveActivities(piId);
                    return null;
                }));
            join(prod, deadlocks, round, label + "/path");
            join(cancel, deadlocks, round, label + "/cancel");
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * A deadlock loser is RECORDED (not rethrown) so the run finishes and the
     * final assert reports every round that deadlocked; any other failure is a
     * real defect and must fail the test loudly.
     */
    private void join(Future<?> f, List<String> deadlocks, int round, String side) throws Exception {
        try {
            f.get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (recordIfDeadlock(e, deadlocks, round, side)) {
                return;
            }
            throw e;
        }
    }

    private boolean recordIfDeadlock(Throwable e, List<String> deadlocks, int round, String side) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = String.valueOf(t.getMessage()).toLowerCase();
            if (msg.contains("deadlock") || msg.contains("40p01")) {
                deadlocks.add("round=" + round + " side=" + side + ": " + t);
                return true;
            }
        }
        return false;
    }

    // ── fixtures / helpers ────────────────────────────────────────────────────

    /** Deploys a fixture under a unique {@code rel63-*} key (sha256 dedup would collide otherwise). */
    private UUID deploy(String fixture) {
        try {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            String bpmn = Files.readString(Paths.get("src/test/files/" + fixture))
                .replaceAll("(<bpmn:process id=\")([^\"]+)(\")",
                    "$1" + KEY_PREFIX + suffix + "-$2$3");
            UUID id = processDefinitionService.addProcessDefinition(bpmn).getId();
            createdDefinitions.add(id);
            return id;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * RuntimeServiceImpl has no @Transactional of its own — start inside a
     * transaction (mirrors the production REST path and other PG-ITs).
     */
    private UUID startInstance(UUID definitionId) {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(definitionId);
        UUID id = new TransactionTemplate(txManager)
            .execute(status -> runtimeService.startProcessInstance(dto).getId());
        createdInstances.add(id);
        return id;
    }

    /** Forces this instance's timer job due (the fixtures use PT5M/PT10M) and returns it. */
    private TimerJob dueTimerJob(UUID piId) {
        jdbc.update("UPDATE timer_jobs SET due_at = now() - interval '10 seconds' "
            + "WHERE process_instance_id = ?", piId);
        List<TimerJob> jobs = new ArrayList<>();
        for (TimerJob j : dbService.findDueTimerJobs(Instant.now().plusSeconds(3600))) {
            if (piId.equals(j.getProcessInstanceId())) {
                jobs.add(j);
            }
        }
        assertThat(jobs).as("ровно один таймер-джоб у инстанса").hasSize(1);
        return jobs.get(0);
    }

    private static void awaitQuietly(CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
