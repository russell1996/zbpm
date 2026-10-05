package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import com.zorrodev.bpm.engine.service.CompletionDedupStore;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import com.zorrodev.bpm.exchange.ServiceTaskDispatchPhase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-36 (H-2, red-team): дедуп {@code completionId} держится в БД, а не в
 * памяти JVM — иначе он не работает на топологии N&gt;1, которую сам проект
 * гоняет в soak ({@code docker-compose.multi.yml}, 3 реплики на одном PG).
 *
 * <p>Почему обязателен реальный PostgreSQL и ДВА независимых экземпляра стора,
 * а не «два потока одного Caffeine»: в Caffeine-кэше оба вызова видят ОДНО
 * множество, и тест был зелёным на N=1 при структурно сломанном N&gt;1 — ровно
 * та ловушка, на которую red-team и наступил. Здесь экземпляр B создан
 * отдельно (свой {@code JdbcTemplate} на тот же PG) — модель двух реплик,
 * делящих одну БД, и никакой разделяемой памяти между ними.
 *
 * <p>Критерии:
 * <ul>
 *   <li>1 — одинаковый {@code completionId}, поданный ДВУМЯ экземплярами, даёт
 *       ровно одно «принято» (второй видит durable-маркер); это и есть то, что
 *       память не могла дать;</li>
 *   <li>2 — маркер живёт в той же транзакции, что и списание бюджета: откат
 *       транзакции убирает маркер САМ (ручной {@code remove} и «red-team 1.4»
 *       ушли), поэтому потерявшийся сбой можно переиграть тем же
 *       {@code completionId};</li>
 *   <li>3 — TTL-очистка удаляет протухшие маркеры и не трогает свежие.</li>
 * </ul>
 */
@Tag("pg")
class CompletionDedupClusterPgIT extends PostgresIT {

    @Autowired CompletionDedupStore storeA;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource pgDataSource;
    @Autowired PlatformTransactionManager txManager;
    @Autowired RuntimeService runtimeService;
    @Autowired ProcessDefinitionService processDefinitionService;
    @Autowired ActivityRepository activityRepository;
    @Autowired ServiceTaskRepository serviceTaskRepository;

    /** Вторая «реплика»: свой JdbcTemplate на тот же PG, никакой общей памяти. */
    private CompletionDedupStore storeB() {
        return new CompletionDedupStore(new JdbcTemplate(pgDataSource), pgDataSource);
    }

    private void cleanup(String completionId) {
        jdbc.update("DELETE FROM completion_dedup WHERE completion_id = ?", completionId);
    }

    @Test
    void criterion1_twoInstances_sameCompletionId_claimedExactlyOnce() {
        String completionId = "cluster-" + UUID.randomUUID();
        try {
            assertThat(storeA.claim(completionId))
                .as("реплика A первой видит id — маркер её").isTrue();
            assertThat(storeB().claim(completionId))
                .as("реплика B обязан увидеть durable-маркер A и отклонить дубль — "
                    + "при in-memory кэше B был бы пуст и оба вызова прошли бы")
                .isFalse();
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM completion_dedup WHERE completion_id = ?",
                Integer.class, completionId)).isEqualTo(1);
        } finally {
            cleanup(completionId);
        }
    }

    @Test
    void criterion2_rolledBackTransaction_leavesNoMarker_sameIdReplayable() {
        String completionId = "rollback-" + UUID.randomUUID();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        try {
            tx.executeWithoutResult(status -> {
                assertThat(storeA.claim(completionId)).isTrue();
                status.setRollbackOnly();
            });
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM completion_dedup WHERE completion_id = ?",
                Integer.class, completionId))
                .as("откат транзакции убирает маркер САМ — потерявшийся сбой "
                    + "обязан остаться переигрываемым тем же completionId")
                .isEqualTo(0);
            assertThat(storeB().claim(completionId))
                .as("после отката тот же id принимается заново — это и есть смысл "
                    + "INSERT-first в той же транзакции вместо ручного remove")
                .isTrue();
        } finally {
            cleanup(completionId);
        }
    }

    @Test
    void criterion3_ttlCleanup_removesExpiredKeepsFresh() throws Exception {
        String stale = "stale-" + UUID.randomUUID();
        String fresh = "fresh-" + UUID.randomUUID();
        try {
            storeA.claim(stale);
            storeA.claim(fresh);
            // Backdate the stale marker past the cleanup TTL instead of sleeping.
            jdbc.update("UPDATE completion_dedup SET created_at = now() - interval '2 hours' "
                + "WHERE completion_id = ?", stale);

            int deleted = storeA.deleteExpiredBefore(
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1800)));

            // Ассертим СВОИ ключи, а не общее число удалённых строк: база PG общая
            // на весь набор PG-тестов, и «ровно 1» здесь ловило бы чужие
            // протухшие маркеры (первая версия теста так и падала: expected 1,
            // but was 3 — чужие строки от других прогонов того же класса).
            assertThat(storeA.isClaimed(stale))
                .as("протухший маркер удалён очисткой").isFalse();
            assertThat(storeA.isClaimed(fresh))
                .as("свежий маркер очистка не трогает — иначе переотправка старого "
                    + "результата снова прошла бы как новая").isTrue();
            assertThat(deleted)
                .as("очистка что-то удалила (наши протухшие маркеры в счёт входят)")
                .isGreaterThanOrEqualTo(1);
        } finally {
            cleanup(stale);
            cleanup(fresh);
        }
    }

    /**
     * Сквозной критерий WO (2) на живом движке: ОДИН логический сбой, доставленный
     * двумя независимыми экземплярами дедупа, списывает бюджет РОВНО ОДИН раз —
     * даже когда реплики настоящие (каждый со своим транзакционным контекстом).
     *
     * <p>Проигрываёт тот же путь, что и воркер: {@code runtimeService.failServiceTask}
     * с {@code completionId}. Дальше — только БД решает, кто первый.
     */
    @Test
    void criterionWO2_duplicateFailureAcrossReplicas_consumesBudgetOnce() throws Exception {
        UUID definitionId = deployOnce();
        UUID piId = runtimeService.startProcessInstance(
            startDto(definitionId)).getId();
        UUID activityId = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(piId) && a.getBpmnElementId().equals("svc"))
            .map(a -> a.getId()).findFirst().orElseThrow();
        String completionId = "cross-replica-" + UUID.randomUUID();
        try {
            TransactionTemplate tx = new TransactionTemplate(txManager);
            tx.executeWithoutResult(s -> runtimeService.completeServiceTask(activityId,
                java.util.List.of(), ServiceTaskDispatchPhase.START, 0));
            int budgetBefore = serviceTaskRepository.findById(activityId).orElseThrow().getRetriesRemaining();

            // Реплика A: первый FAILED этой отправки.
            tx.executeWithoutResult(s -> runtimeService.failServiceTask(activityId, "boom", null,
                ServiceTaskDispatchPhase.REAL, null, completionId));
            assertThat(retries(activityId)).isEqualTo(budgetBefore - 1);

            // Реплика B (другой инстанс дедупа): confirm-loss переотдал ту же отправку.
            assertThat(storeB().claim(completionId))
                .as("вторая реплика видит durable-маркер первой")
                .isFalse();
            assertThat(retries(activityId))
                .as("бюджет списывается РОВНО один раз на логический сбой")
                .isEqualTo(budgetBefore - 1);

            // НОВАЯ отправка (редispatch) приходит с новым id — цикл ретраев не застревает.
            String nextSend = "cross-replica-next-" + UUID.randomUUID();
            tx.executeWithoutResult(s -> runtimeService.failServiceTask(activityId, "boom again", null,
                ServiceTaskDispatchPhase.REAL, null, nextSend));
            assertThat(retries(activityId))
                .as("новая отправка расходует бюджет — ретраи продолжаются")
                .isEqualTo(budgetBefore - 2);
        } finally {
            cleanup(completionId);
        }
    }

    /**
     * WO-C8-36 (F-1, red-team раунда 2): конфликт дедупа НЕ обнуляет транзакцию
     * вызывающего на PostgreSQL.
     *
     * <p>Воспроизведение дефекта, которое red-team принёс на живой СУБД: дубль PK
     * (SQLState 23505) переводит транзакцию в aborted, {@code commit()} pgjdbc
     * возвращает {@code ROLLBACK} <b>без исключения</b>, и Spring считает, что
     * закоммитилось. Итог — всё, что записано до claim'а, исчезает молча.
     *
     * <p>Здесь claim вызывается ПОСЛЕ записи в той же транзакции — то есть
     * ровно та позиция, которая сегодня пуста. Это делает проверку сильнее
     * требования «инвариант „claim — первая операция“ соблюдён“: тест не
     * полагается на него, а показывает, что конфликт безопасен в ЛЮБОЙ позиции.
     * Мутация «вернуть {@code catch DuplicateKeyException} на PG» (старая
     * реализация) валит оба ассерта этого теста: маркер-проба исчезает.
     */
    @Test
    void criterionF1_duplicateClaim_keepsCallerTransactionWritable() {
        String claimed = "f1-claimed-" + UUID.randomUUID();
        String probe = "f1-probe-" + UUID.randomUUID();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        try {
            // Реплика A первой захватывает id (в своей транзакции — autocommit у JdbcTemplate
            // без Spring-транзакции, поэтому маркер сразу виден всем).
            assertThat(storeA.claim(claimed)).isTrue();

            // Реплика B: её собственная запись В ТОЙ ЖЕ транзакции, затем дубль claim'а.
            tx.executeWithoutResult(status -> {
                assertThat(storeA.claim(probe))
                    .as("запись вызывающего до конфликта — обычный захват")
                    .isTrue();
                assertThat(storeB().claim(claimed))
                    .as("этот id уже занят репликой A — дубль")
                    .isFalse();
                assertThat(jdbc.update(
                        "UPDATE completion_dedup SET created_at = created_at WHERE completion_id = ?", probe))
                    .as("транзакция после дубля обязана остаться пригодной к записи — "
                        + "на PG aborted-состояние отвергло бы эту команду с 25P02")
                    .isEqualTo(1);
            });

            assertThat(storeA.isClaimed(probe))
                .as("запись реплики B до конфликта обязана пережить commit. С откатом "
                    + "DuplicateKeyException она исчезала бы МОЛЧА: commit() на aborted-"
                    + "транзакции возвращается без ошибки, и Spring не узнаёт о потере")
                .isTrue();
        } finally {
            cleanup(claimed);
            cleanup(probe);
        }
    }

    /**
     * WO-C8-36 (F-1): два РЕАЛЬНЫХ потока, каждый в своей транзакции, ловят ОДИН
     * {@code completionId} — ровно один расход бюджета, и ни одна транзакция не
     * теряет собственные записи.
     *
     * <p>Почему это не «два JdbcTemplate без транзакций» (как было в
     * {@code criterionWO2_duplicateFailureAcrossReplicas}): там проигравший не
     * имеет ничего, что можно потерять, поэтому aborted-транзакция была
     * невидима. Здесь у каждого потока своя запись-проба, сделанная ДО claim'а, —
     * ровно то, что в бою потерялось бы на PG. Заодно это настоящая
     * конкуренция: {@code CyclicBarrier} стартует оба потока одновременно.
     */
    @Test
    void criterionF1_twoRealTransactions_duplicateSpendsBudgetOnceAndLosesNoWrites()
            throws Exception {
        UUID definitionId = deployOnce();
        UUID piId = runtimeService.startProcessInstance(startDto(definitionId)).getId();
        UUID activityId = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(piId) && a.getBpmnElementId().equals("svc"))
            .map(a -> a.getId()).findFirst().orElseThrow();
        String completionId = "f1-concurrent-" + UUID.randomUUID();
        TransactionTemplate setup = new TransactionTemplate(txManager);
        setup.executeWithoutResult(s -> runtimeService.completeServiceTask(activityId,
            java.util.List.of(), ServiceTaskDispatchPhase.START, 0));
        int budgetBefore = retries(activityId);

        String probeA = "f1-probe-a-" + UUID.randomUUID();
        String probeB = "f1-probe-b-" + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier startTogether = new CyclicBarrier(2);
            List<Future<?>> results = new ArrayList<>();
            for (String probe : List.of(probeA, probeB)) {
                results.add(pool.submit(() -> {
                    startTogether.await(20, TimeUnit.SECONDS);
                    // СВОЯ транзакция этого потока и СВОЯ запись в ней (проба), затем —
                    // настоящий боевой путь движка с ОБЩИМ completionId.
                    TransactionTemplate own = new TransactionTemplate(txManager);
                    own.executeWithoutResult(status -> {
                        storeA.claim(probe);
                        runtimeService.failServiceTask(activityId, "boom", null,
                            ServiceTaskDispatchPhase.REAL, null, completionId);
                    });
                    return null;
                }));
            }
            for (Future<?> f : results) {
                f.get(30, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            throw new AssertionError("конкурентные транзакции дедупа упали: " + e, e);
        } finally {
            pool.shutdownNow();
        }

        assertThat(storeA.isClaimed(probeA))
            .as("собственная запись транзакции A пережила commit — на старой реализации "
                + "25P02 делал транзакцию aborted, и commit() молча уводил её в ROLLBACK")
            .isTrue();
        assertThat(storeA.isClaimed(probeB))
            .as("собственная запись транзакции B пережила commit")
            .isTrue();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM completion_dedup WHERE completion_id = ?",
            Integer.class, completionId))
            .as("маркер на логический сбой ровно один")
            .isEqualTo(1);
        assertThat(retries(activityId))
            .as("два РЕАЛЬНЫХ конкурентных failServiceTask с одним completionId тратят "
                + "бюджет ровно один раз")
            .isEqualTo(budgetBefore - 1);

        // НОВАЯ отправка (редispatch) приходит с новым id — цикл ретраев не застревает.
        TransactionTemplate tx = new TransactionTemplate(txManager);
        String nextSend = "f1-next-" + UUID.randomUUID();
        tx.executeWithoutResult(s -> runtimeService.failServiceTask(activityId, "boom again", null,
            ServiceTaskDispatchPhase.REAL, null, nextSend));
        assertThat(retries(activityId))
            .as("новая отправка расходует бюджет — цикл ретраев не застревает")
            .isEqualTo(budgetBefore - 2);
    }

    private int retries(UUID activityId) {
        return serviceTaskRepository.findById(activityId).orElseThrow().getRetriesRemaining();
    }

    private StartProcessInstanceDTO startDto(UUID definitionId) {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(definitionId);
        return dto;
    }

    private UUID deployOnce() throws Exception {
        String xml = Files.readString(Paths.get("src/test/files/test-c8-execution-listeners.bpmn"))
            .replace("          <zeebe:executionListener eventType=\"end\" type=\"listener-job\" />\n", "")
            .replace("c8-exec-listeners", "c8dedup-" + UUID.randomUUID().toString().substring(0, 8));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(xml);
        return model.getId();
    }

    @Test
    void markerTable_existsWithExpectedColumns() {
        // Не «схема угадана»: имена колонок — из changeset'а, а прогон на реальной PG
        // доказывает, что они и правда такие (раньше здесь была бы проверка на H2).
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.columns WHERE table_name = 'completion_dedup' "
                + "AND column_name IN ('completion_id', 'created_at')", Integer.class)).isEqualTo(2);
    }

    @Test
    void activityStatusAfterDuplicate_isNotCompleted() throws Exception {
        // Контроль дефекта для сквозного теста: без дедупа активность завершалась бы.
        UUID definitionId = deployOnce();
        UUID piId = runtimeService.startProcessInstance(startDto(definitionId)).getId();
        UUID activityId = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(piId) && a.getBpmnElementId().equals("svc"))
            .map(a -> a.getId()).findFirst().orElseThrow();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(s -> runtimeService.completeServiceTask(activityId,
            java.util.List.of(), ServiceTaskDispatchPhase.START, 0));
        String completionId = "status-" + UUID.randomUUID();
        try {
            tx.executeWithoutResult(s -> runtimeService.failServiceTask(activityId, "boom", null,
                ServiceTaskDispatchPhase.REAL, null, completionId));
            ActivityStatus status = activityRepository.findAll().stream()
                .filter(a -> a.getId().equals(activityId)).findFirst().orElseThrow().getStatus();
            assertThat(status).isEqualTo(ActivityStatus.CREATED);
        } finally {
            cleanup(completionId);
        }
    }
}
