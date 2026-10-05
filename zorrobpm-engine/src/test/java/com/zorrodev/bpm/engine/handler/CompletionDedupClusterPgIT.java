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
import java.util.UUID;

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
        return new CompletionDedupStore(new JdbcTemplate(pgDataSource));
    }

    private void cleanup(String completionId) {
        jdbc.update("DELETE FROM completion_dedup WHERE completion_id = ?", completionId);
    }

    @Test
    void criterion1_twoInstances_sameCompletionId_claimedExactlyOnce() {
        String completionId = "cluster-" + UUID.randomUUID();
        try {
            assertThat(storeA.claim(completionId, 60))
                .as("реплика A первой видит id — маркер её").isTrue();
            assertThat(storeB().claim(completionId, 60))
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
                assertThat(storeA.claim(completionId, 60)).isTrue();
                status.setRollbackOnly();
            });
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM completion_dedup WHERE completion_id = ?",
                Integer.class, completionId))
                .as("откат транзакции убирает маркер САМ — потерявшийся сбой "
                    + "обязан остаться переигрываемым тем же completionId")
                .isEqualTo(0);
            assertThat(storeB().claim(completionId, 60))
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
            storeA.claim(stale, 60);
            storeA.claim(fresh, 3600);
            // Backdate the stale marker past the cleanup TTL instead of sleeping.
            jdbc.update("UPDATE completion_dedup SET created_at = now() - interval '2 hours' "
                + "WHERE completion_id = ?", stale);

            int deleted = storeA.deleteExpiredBefore(
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1800)));

            assertThat(deleted).as("очистка удаляет протухшие маркеры").isEqualTo(1);
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM completion_dedup WHERE completion_id = ?",
                Integer.class, fresh))
                .as("свежий маркер не трогаем").isEqualTo(1);
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
            assertThat(storeB().claim(completionId, 60))
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
