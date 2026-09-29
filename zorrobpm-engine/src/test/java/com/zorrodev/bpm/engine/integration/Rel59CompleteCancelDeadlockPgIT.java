package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.service.DBService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
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
 * WO-REL-59: параллельные {@code complete} × {@code cancel} того же экземпляра.
 *
 * <p>Без фикса: complete-путь держит row-lock activity ({@code FOR UPDATE OF}
 * с одним алиасом — см. {@code Rel59SqlProbePgIT}) и просит instance-лок,
 * cancel-путь держит instance-лок и {@code UPDATE activities} просит
 * activity-лок — классический ABBA-deadlock, проигравший получает
 * {@code deadlock detected} (SQLState 40P01) через {@code deadlock_timeout}
 * (~1с). С фиксом (единый порядок instance→activity,
 * {@code ElementSupport.lockInstanceFirst}) второй участник просто ждёт
 * коммита первого — сериализация вместо deadlock.
 *
 * <p>V6: два реальных потока, две реальные транзакции, {@code CyclicBarrier}
 * на старте + sleep между первым и вторым захватом (окно deadlock
 * гарантировано открыто у обеих сторон одновременно). 7 раундов подряд
 * (критерий 3 WO: не меньше 5).
 *
 * <p>P-67: мутация «вернуть activity-first» (lockAndReload + late
 * lockProcessInstance) обязана валить тест — проверено POF: на дереве до
 * фикса один из потоков ловит deadlock (40P01) уже в первом раунде.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles({"test", "pgtest"})
@Tag("pg")
class Rel59CompleteCancelDeadlockPgIT {

    private static final int ROUNDS = 7;

    @DynamicPropertySource
    static void pgProperties(DynamicPropertyRegistry registry) {
        String host = cfg("PG_HOST", "127.0.0.1");
        String port = cfg("PG_PORT", "5432");
        String db = cfg("PG_DB", "zorrobpm-db");
        String user = cfg("PG_USER", "zorrodev");
        String pass = cfg("PG_PASSWORD", "zorrodev");

        registry.add("spring.datasource.url",
            () -> "jdbc:postgresql://" + host + ":" + port + "/" + db + "?sslmode=disable");
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> pass);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.liquibase.enabled", () -> "true");
        registry.add("spring.hikari.connection-timeout", () -> "60000");
    }

    private static String cfg(String key, String dflt) {
        String v = System.getenv(key);
        if (v == null) {
            v = System.getProperty(key);
        }
        return v != null ? v : dflt;
    }

    @Autowired DBService dbService;
    @Autowired com.zorrodev.bpm.engine.handler.ElementSupport elementSupport;
    @Autowired ActivityRepository activityRepository;
    @Autowired ProcessInstanceRepository processInstanceRepository;
    @Autowired ProcessDefinitionRepository processDefinitionRepository;
    @Autowired com.zorrodev.bpm.engine.repository.TokenRepository tokenRepository;
    @Autowired PlatformTransactionManager txManager;

    @Test
    void cancelVsComplete_noDeadlockSevenRounds() throws Exception {
        List<String> deadlockHits = new CopyOnWriteArrayList<>();
        for (int round = 0; round < ROUNDS; round++) {
            runOneRound(round, deadlockHits);
        }
        assertThat(deadlockHits)
            .as("0 deadlock за " + ROUNDS + " раундов")
            .isEmpty();
    }

    private void runOneRound(int round, List<String> deadlockHits) throws Exception {
        UUID pdId = UUID.randomUUID();
        ProcessDefinitionEntity pd = new ProcessDefinitionEntity();
        pd.setId(pdId);
        pd.setKey("rel59race" + round + UUID.randomUUID());
        pd.setVersion(1);
        pd.setCreatedAt(Instant.now());
        pd.setSha256(UUID.randomUUID().toString());
        pd.setName("race");
        processDefinitionRepository.saveAndFlush(pd);

        UUID piId = UUID.randomUUID();
        ProcessInstanceEntity pi = new ProcessInstanceEntity();
        pi.setId(piId);
        pi.setProcessDefinitionId(pdId);
        pi.setStartedAt(Instant.now());
        pi.setCancelled(false);
        processInstanceRepository.saveAndFlush(pi);

        UUID activityId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        com.zorrodev.bpm.engine.entity.TokenEntity tok =
            new com.zorrodev.bpm.engine.entity.TokenEntity();
        tok.setId(tokenId);
        tokenRepository.saveAndFlush(tok);
        ActivityEntity a = new ActivityEntity();
        a.setId(activityId);
        a.setProcessInstanceId(piId);
        a.setToken(tokenId);
        a.setBpmnElementId("userTask");
        a.setStatus(ActivityStatus.CREATED);
        a.setCreatedAt(Instant.now());
        activityRepository.saveAndFlush(a);

        try {
            CyclicBarrier start = new CyclicBarrier(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                // Поток A — complete-путь: НАСТОЯЩИЙ прод-метод
                // {@code ElementSupport.lockInstanceFirst} (тот же вызов, что
                // делают CompletionService.completeUserTask/completeServiceTask)
                // + завершение. Порядок захватов живёт ВНУТРИ прод-метода —
                // мутация его тела (activity-first) валит этот тест без
                // единой правки теста (G-N). Окно deadlock держит sleep
                // cancel-стороны: оба участника успевают взять каждый свой
                // первый лок до того, как кто-то попросит второй.
                Future<?> complete = pool.submit(() -> {
                    new TransactionTemplate(txManager).execute(status -> {
                        awaitQuietly(start);
                        elementSupport.lockInstanceFirst(activityId);
                        dbService.completeActivity(activityId);
                        return null;
                    });
                });
                // Поток B — cancel-путь
                // (ProcessInstanceRuntimeOperationsImpl.cancelProcessInstance,
                // сокращённый до lock-скелета: instance-lock → sleep →
                // UPDATE activities).
                Future<?> cancel = pool.submit(() -> {
                    new TransactionTemplate(txManager).execute(status -> {
                        awaitQuietly(start);
                        dbService.lockProcessInstance(piId);
                        sleepQuietly(500);
                        dbService.cancelActiveActivities(piId);
                        return null;
                    });
                });
                try {
                    complete.get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    recordIfDeadlock(e, deadlockHits, round, "complete");
                    throw e;
                }
                try {
                    cancel.get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    recordIfDeadlock(e, deadlockHits, round, "cancel");
                    throw e;
                }
            } finally {
                pool.shutdownNow();
            }
        } finally {
            activityRepository.deleteById(activityId);
            processInstanceRepository.deleteById(piId);
            processDefinitionRepository.deleteById(pdId);
            tokenRepository.deleteById(tokenId);
        }
    }

    private void recordIfDeadlock(Exception e, List<String> deadlockHits, int round, String side) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = String.valueOf(t.getMessage()).toLowerCase();
            if (msg.contains("deadlock") || msg.contains("40p01")) {
                deadlockHits.add("round=" + round + " side=" + side + ": " + t);
                return;
            }
        }
    }

    private static void awaitQuietly(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
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
