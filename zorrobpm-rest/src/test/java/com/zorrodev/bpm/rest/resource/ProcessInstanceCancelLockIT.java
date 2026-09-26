package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.TimerJobRepository;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.AuditLogService;
import com.zorrodev.bpm.engine.service.DBService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * WO-ACL-21 (б): отмена сериализуется с timer-fire через instance-lock.
 *
 * <p>Два реальных потока/транзакции (V6): поток B — in-flight timer-fire
 * (дословное зеркало {@code TimerJobExecutor.fire:61-65}: тот же
 * {@code findByIdForUpdate} + cancelled-guard, тот же re-arm INSERT;
 * claim-шаг опущен осознанно — его UPDATE строки timer_jobs, которую отмена
 * уже удалила, сериализовал бы потоки на строке таймера, а не на строке
 * экземпляра, и тест не доказывал бы ничего про lock отмены — честная
 * граница, см. отчёт), поток A — настоящий
 * {@code ProcessInstanceRuntimeOperationsImpl.cancelProcessInstance}
 * (реальный бин, реальный прод-путь).
 *
 * <p>Форсированный интерливинг (детерминирован латчами, не таймингом): B
 * берёт guard-lock и видит живой экземпляр → A выполняет отмену →
 * B вставляет re-arm СТРОГО ПОСЛЕ завершённого DELETE отмены
 * ({@code A_DELETED_DONE}): без lock'а в отмене DELETE уже позади и INSERT
 * B ложится поверх → зомби-таймер на отменённом экземпляре (POF RED).
 * С lock'ом A ждёт у входа, пока B держит guard-lock; B по таймауту идёт
 * дальше сам (как настоящий fire, не дождавшийся очереди), вставляет и
 * коммитит — и только потом A проходит и своим DELETE сносит в том числе
 * строку B: сериализация через lock, зомби нет (GREEN).
 * Крюк на DELETE — spy поверх реального бина (всё остальное — настоящий
 * прод-путь, см. прецедент DeployFormTransactionIT).
 */
@SpringBootTest(properties = {
    // Изолированная mem-БД: чужой сид не мешает + LOCK_TIMEOUT с запасом —
    // потоки in-flight держат FOR UPDATE дольше дефолтных 1-2с H2.
    "spring.datasource.url=jdbc:h2:mem:acl21;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000"
})
@ActiveProfiles("test")
class ProcessInstanceCancelLockIT {

    /**
     * Отмена завершила свой DELETE таймеров (крюк на spy) — после этой точки
     * любой re-arm INSERT ляжет поверх удалённых (мутант без lock: зомби).
     */
    static final CountDownLatch A_DELETED_DONE = new CountDownLatch(1);
    /** Fire закоммитил свой re-arm (освобождает guard-lock). */
    static final CountDownLatch B_COMMITTED = new CountDownLatch(1);

    @Autowired private ProcessInstanceRuntimeOperations processInstanceRuntimeOperations;
    @Autowired private ProcessInstanceRepository processInstanceRepository;
    @Autowired private ProcessDefinitionRepository processDefinitionRepository;
    @Autowired private TimerJobRepository timerJobRepository;
    @MockitoSpyBean private DBService dbService;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private AuditLogService auditLogService;
    @MockitoBean private RuntimeOperationSupport runtimeOperationSupport;

    private UUID cleanupPi, cleanupPd;

    @AfterEach
    void cleanup() {
        if (cleanupPi != null) processInstanceRepository.deleteById(cleanupPi);
        if (cleanupPd != null) processDefinitionRepository.deleteById(cleanupPd);
        cleanupPi = cleanupPd = null;
    }

    @Test
    void cancel_serialisesWithTimerFireGuard_noZombieRearm() throws Exception {
        UUID pdId = UUID.randomUUID();
        ProcessDefinitionEntity pd = new ProcessDefinitionEntity();
        pd.setId(pdId);
        pd.setKey("k-" + pdId.toString().substring(0, 8));
        pd.setVersion(1);
        pd.setCreatedAt(Instant.now());
        pd.setSha256(UUID.randomUUID().toString());
        pd.setName("test");
        pd.setDeploymentState("ACTIVE");
        processDefinitionRepository.saveAndFlush(pd);
        cleanupPd = pdId;

        UUID piId = UUID.randomUUID();
        ProcessInstanceEntity pi = new ProcessInstanceEntity();
        pi.setId(piId);
        pi.setProcessDefinitionId(pdId);
        pi.setStartedAt(Instant.now());
        pi.setInitiator("test");
        pi.setCompletedAt(null);
        processInstanceRepository.saveAndFlush(pi);
        cleanupPi = piId;

        // Живой таймер экземпляра (отмена его удалит; concurrent re-arm поверх
        // удалённых создал бы зомби-строку).
        UUID timerJobId = dbService.createTimerJob(null, Instant.now().minusSeconds(5),
            null, 2, "R3/PT1H", piId);

        when(runtimeOperationSupport.resolveDefinitionKeyByInstance(piId))
            .thenReturn("k-" + pdId.toString().substring(0, 8));
        org.mockito.Mockito.doNothing().when(runtimeOperationSupport)
            .requireOperate(any(), any());
        when(runtimeOperationSupport.getPrincipal()).thenReturn(
            new Principal.UserPrincipal(UUID.randomUUID(), "test", "USER"));
        // Крюк на DELETE: реальный метод выполняется ПЕРВЫМ, и только потом
        // fire получает разрешение вставлять (строгий порядок DELETE→INSERT,
        // никакой гонки statement'ов — исход детерминирован lock'ом, не H2).
        doAnswer(inv -> {
            Object result = inv.callRealMethod();
            A_DELETED_DONE.countDown();
            return result;
        }).when(dbService).deleteTimerJobsByProcessInstanceId(any());

        CountDownLatch guardHeld = new CountDownLatch(1);
        AtomicReference<Boolean> guardSawLive = new AtomicReference<>();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // B: in-flight fire — guard-lock + чтение живого экземпляра,
            // затем re-arm СТРОГО ПОСЛЕ завершённого DELETE отмены. С lock'ом
            // в отмене DELETE не наступает никогда (отмена ждёт у входа) —
            // уходим по таймауту и вставляем ДО отмены: её DELETE потом снесёт
            // и нашу строку (сериализация, GREEN). Без lock'а DELETE уже
            // позади — наш INSERT ложится поверх → зомби (POF RED).
            Future<?> fireFuture = pool.submit(() -> {
                tx.execute(status -> {
                    var locked = processInstanceRepository.findByIdForUpdate(piId).orElse(null);
                    guardSawLive.set(locked != null && !locked.isCancelled()
                        && locked.getCompletedAt() == null);
                    guardHeld.countDown();
                    try {
                        A_DELETED_DONE.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    dbService.createTimerJob(null, Instant.now().plusSeconds(3600),
                        null, 1, "R3/PT1H", piId);
                    return null;
                });
                B_COMMITTED.countDown();
                return null;
            });
            assertThat(guardHeld.await(10, TimeUnit.SECONDS)).isTrue();

            // A: настоящая отмена, стартует строго когда fire уже in-flight.
            Future<?> cancelFuture = pool.submit(() -> {
                processInstanceRuntimeOperations.cancelProcessInstance(piId);
                return null;
            });

            cancelFuture.get(20, TimeUnit.SECONDS);
            fireFuture.get(20, TimeUnit.SECONDS);

            // Уязвимый интерливинг реально был настроен: fire видел живой
            // экземпляр (иначе тест вакуумен — guard нечего было пропускать).
            assertThat(guardSawLive.get())
                .as("setup guard: in-flight fire must observe a live instance")
                .isTrue();

            // Отмена дошла до конца, re-arm поверх удалённых не выжил.
            ProcessInstanceEntity after = processInstanceRepository.findById(piId).orElse(null);
            assertThat(after).isNotNull();
            assertThat(after.isCancelled()).isTrue();
            assertThat(timerJobRepository.findAll(TimerJobRepository.byProcessInstanceId(piId)))
                .as("no zombie timer jobs on cancelled instance")
                .isEmpty();
            // Санитарный след: исходный таймер тоже удалён отменой, а не потерян.
            assertThat(timerJobRepository.findById(timerJobId)).isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }
}
