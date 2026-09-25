package com.zorrodev.bpm.engine.metrics;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.dto.TimerJob;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.entity.OutboxKind;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.TimerJobEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.TimerJobRepository;
import com.zorrodev.bpm.engine.scheduler.OutboxBatchProcessor;
import com.zorrodev.bpm.engine.scheduler.OutboxDeliveryResultListener;
import com.zorrodev.bpm.engine.scheduler.TimerJobExecutor;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import com.zorrodev.bpm.exchange.OutboxDeliveryResult;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * WO-OBS-1 (revival), criterion 1 — wiring proof: every meter moves through its REAL
 * prod path (not direct {@code BpmMetrics} calls — those are {@link BpmMetricsTest}).
 * Counters assert DELTAS (the registry is context-shared, values accumulate across
 * tests); gauges assert round-trips from a captured baseline. Every started instance
 * is terminally closed inside its test so the active gauge balances.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class MetricsWiringIntegrationTests {

    @Autowired private MeterRegistry meterRegistry;
    @Autowired private com.zorrodev.bpm.engine.metrics.BpmMetrics contextMetrics;
    @Autowired private jakarta.persistence.EntityManager entityManager;
    @Autowired private RuntimeService runtimeService;
    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private DBService dbService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private OutboxRepository outboxRepository;
    @Autowired private ProcessInstanceRepository processInstanceRepository;
    @Autowired private ProcessDefinitionRepository processDefinitionRepository;
    @Autowired private TimerJobRepository timerJobRepository;
    @Autowired private TimerJobExecutor timerJobExecutor;
    @Autowired private TransactionTemplate transactionTemplate;

    private double counter(String name) {
        return meterRegistry.find(name).counter().count();
    }

    private double gauge(String name) {
        return meterRegistry.find(name).gauge().value();
    }

    private UUID deploy(String fixture) throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/" + fixture));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        return model.getId();
    }

    private UUID start(UUID pdId) {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        dto.setVariables(List.of());
        return runtimeService.startProcessInstance(dto).getId();
    }

    @Transactional
    @Test
    void processStarted_completedAndActiveGauge_roundTrip() throws Exception {
        // dummy-process runs start→end synchronously: +1 started, +1 completed, gauge back.
        UUID pdId = deploy("dummy-process.bpmn");
        double startedBefore = counter("zbpm.process.started");
        double completedBefore = counter("zbpm.process.completed");
        double activeBefore = gauge("zbpm.process.instances.active");

        start(pdId);

        assertThat(counter("zbpm.process.started") - startedBefore).isEqualTo(1.0);
        assertThat(counter("zbpm.process.completed") - completedBefore).isEqualTo(1.0);
        assertThat(gauge("zbpm.process.instances.active")).isEqualTo(activeBefore);
    }

    @Transactional
    @Test
    void cancelInstance_decrementsActiveGaugeWithoutCompleted() throws Exception {
        // Parking process: start parks on taskM, cancel drains the gauge (no completed++).
        UUID pdId = deploy("test-metrics-park.bpmn");
        double completedBefore = counter("zbpm.process.completed");
        double activeBefore = gauge("zbpm.process.instances.active");

        UUID pi = start(pdId);
        assertThat(gauge("zbpm.process.instances.active")).isEqualTo(activeBefore + 1);

        dbService.cancelProcessInstance(pi);

        assertThat(gauge("zbpm.process.instances.active")).isEqualTo(activeBefore);
        assertThat(counter("zbpm.process.completed") - completedBefore).isEqualTo(0.0);
    }

    @Transactional
    @Test
    void incidentRaise_complete_incidentFailedAndStuckPair() throws Exception {
        UUID pdId = deploy("test-metrics-park.bpmn");
        UUID pi = start(pdId);
        UUID taskActivity = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi) && a.getBpmnElementId().equals("taskM"))
            .map(ActivityEntity::getId).findFirst().orElseThrow();
        double failedBefore = counter("zbpm.process.failed");
        double stuckBefore = gauge("zbpm.tokens.stuck");

        UUID incidentId = dbService.createIncident(taskActivity, "boom");
        assertThat(counter("zbpm.process.failed") - failedBefore).isEqualTo(1.0);
        assertThat(gauge("zbpm.tokens.stuck")).isEqualTo(stuckBefore + 1);

        dbService.completeIncident(incidentId);
        assertThat(gauge("zbpm.tokens.stuck")).isEqualTo(stuckBefore);

        // Batch close path: two opens closed by one call decrement twice.
        UUID second = dbService.createIncident(taskActivity, "boom-2");
        assertThat(gauge("zbpm.tokens.stuck")).isEqualTo(stuckBefore + 1);
        dbService.completeIncidentsByActivityIds(List.of(taskActivity));
        assertThat(gauge("zbpm.tokens.stuck")).isEqualTo(stuckBefore);

        // Double close of the same incident decrements once (wasOpen guard).
        dbService.completeIncident(incidentId);
        assertThat(gauge("zbpm.tokens.stuck")).isEqualTo(stuckBefore);

        // Balance the active gauge for the next tests.
        dbService.cancelProcessInstance(pi);
    }

    @Transactional
    @Test
    void outboxAck_marksPublishedCounter() {
        OutboxEntry entry = new OutboxEntry();
        entry.setId(UUID.randomUUID());
        entry.setKind(OutboxKind.DOMAIN_EVENT);
        entry.setPayload("{\"type\":\"x\"}");
        entry.setCreatedAt(Instant.now());
        entry.setPublished(false);
        outboxRepository.save(entry);
        double before = counter("zbpm.outbox.published");

        OutboxDeliveryResultListener listener = new OutboxDeliveryResultListener(outboxRepository, contextMetrics, mock(com.zorrodev.bpm.engine.event.DomainEventEmitter.class));
        OutboxDeliveryResult result = new OutboxDeliveryResult();
        result.setOutboxId(entry.getId().toString());
        result.setAcked(true);
        listener.on(result);

        entityManager.clear(); // bulk UPDATE bypasses L1 — re-read
        assertThat(outboxRepository.findById(entry.getId()).orElseThrow().isPublished()).isTrue();
        assertThat(counter("zbpm.outbox.published") - before).isEqualTo(1.0);
    }

    @Transactional
    @Test
    void outboxNack_paths_splitRabbitAndQuarantine() {
        OutboxEntry retry = new OutboxEntry();
        retry.setId(UUID.randomUUID());
        retry.setKind(OutboxKind.DOMAIN_EVENT);
        retry.setPayload("{\"type\":\"x\"}");
        retry.setCreatedAt(Instant.now());
        retry.setPublished(false);
        retry.setAttempts(0);
        outboxRepository.save(retry);
        OutboxEntry dead = new OutboxEntry();
        dead.setId(UUID.randomUUID());
        dead.setKind(OutboxKind.DOMAIN_EVENT);
        dead.setPayload("{\"type\":\"x\"}");
        dead.setCreatedAt(Instant.now());
        dead.setPublished(false);
        dead.setAttempts(5);
        outboxRepository.save(dead);
        double rabbitBefore = counter("zbpm.rabbit.publish.failures");
        double failedBefore = counter("zbpm.outbox.failed");

        OutboxDeliveryResultListener listener = new OutboxDeliveryResultListener(outboxRepository, contextMetrics, mock(com.zorrodev.bpm.engine.event.DomainEventEmitter.class));
        // Manual construction skips @Value injection (maxRetries would stay 0) — same
        // ReflectionTestUtils trick as OutboxBatchProcessorKindTest.
        org.springframework.test.util.ReflectionTestUtils.setField(listener, "maxRetries", 5);
        OutboxDeliveryResult nackRetry = new OutboxDeliveryResult();
        nackRetry.setOutboxId(retry.getId().toString());
        nackRetry.setAcked(false);
        nackRetry.setCause("connection reset");
        listener.on(nackRetry);
        OutboxDeliveryResult nackDead = new OutboxDeliveryResult();
        nackDead.setOutboxId(dead.getId().toString());
        nackDead.setAcked(false);
        nackDead.setCause("connection reset");
        listener.on(nackDead);

        assertThat(counter("zbpm.rabbit.publish.failures") - rabbitBefore).isEqualTo(1.0);
        assertThat(counter("zbpm.outbox.failed") - failedBefore).isEqualTo(1.0);
        entityManager.clear(); // bulk UPDATE bypasses L1 — re-read
        assertThat(outboxRepository.findById(dead.getId()).orElseThrow().getStatus()).isEqualTo(com.zorrodev.bpm.engine.entity.OutboxStatus.FAILED);
    }

    @Transactional
    @Test
    void processBatch_samplesBacklogAndQuarantineGauges() {
        // Deterministic counts: wipe first (rolled back afterwards with everything else).
        outboxRepository.deleteAllInBatch();
        for (int i = 0; i < 2; i++) {
            OutboxEntry pending = new OutboxEntry();
            pending.setId(UUID.randomUUID());
            pending.setKind(OutboxKind.DOMAIN_EVENT);
            pending.setPayload("{\"type\":\"x\"}");
            pending.setCreatedAt(Instant.now());
            pending.setPublished(false);
            outboxRepository.save(pending);
        }
        OutboxEntry quarantined = new OutboxEntry();
        quarantined.setId(UUID.randomUUID());
        quarantined.setKind(OutboxKind.DOMAIN_EVENT);
        quarantined.setPayload("{\"type\":\"x\"}");
        quarantined.setCreatedAt(Instant.now());
        quarantined.setPublished(false);
        quarantined.setStatus(com.zorrodev.bpm.engine.entity.OutboxStatus.FAILED);
        outboxRepository.save(quarantined);

        // Real processor + real repo + real metrics; only the event bus is mocked
        // (published events would otherwise fan out into handlers mid-test).
        OutboxBatchProcessor processor = new OutboxBatchProcessor(outboxRepository,
            mock(ApplicationEventPublisher.class), new tools.jackson.databind.ObjectMapper(), contextMetrics,
            mock(com.zorrodev.bpm.engine.event.DomainEventEmitter.class),
            com.zorrodev.bpm.engine.tracing.TracingSupport.noop());
        org.springframework.test.util.ReflectionTestUtils.setField(processor, "batchSize", 100);
        org.springframework.test.util.ReflectionTestUtils.setField(processor, "maxRetries", 5);
        // Fresh counter: this processor's first tick samples by construction.
        org.springframework.test.util.ReflectionTestUtils.setField(processor, "tickCounter",
            new java.util.concurrent.atomic.AtomicLong(0));

        // WO-QW-1 A-C-5b: gauges are deferred to afterCommit, so inside this
        // @Transactional test they land only when the synchronizations fire —
        // fire the ones processBatch registered (commit itself rolls back at
        // test end). Deltas, not absolutes: the registry is context-shared.
        // This IS the deferral proof: with inline sampling the values would
        // already have moved and no synchronization would be registered.
        double backlogBefore = gauge("zbpm.outbox.backlog");
        double quarantineBefore = gauge("zbpm.outbox.quarantine");
        var syncsBefore = new java.util.ArrayList<>(
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations());
        processor.processBatch();
        var added = new java.util.ArrayList<>(
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations());
        added.removeAll(syncsBefore);
        assertThat(added)
            .as("processBatch must defer gauge sampling to afterCommit, not sample inline")
            .hasSize(1);
        assertThat(gauge("zbpm.outbox.backlog")).isEqualTo(backlogBefore);
        assertThat(gauge("zbpm.outbox.quarantine")).isEqualTo(quarantineBefore);
        added.forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);

        assertThat(gauge("zbpm.outbox.backlog")).isEqualTo(backlogBefore + 2.0);
        assertThat(gauge("zbpm.outbox.quarantine")).isEqualTo(quarantineBefore + 1.0);
    }

    @Test
    void timerFire_recordsLag() {
        // Mirror Rel4ConcurrencyTest: cancelled instance + past-due row + real
        // Spring-managed TimerJobExecutor — the lag hook runs at claim, before the
        // cancelled-path early exit. Count only (values are timing).
        UUID pdId = transactionTemplate.execute(status -> {
            com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity pd =
                new com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity();
            pd.setId(UUID.randomUUID());
            pd.setKey("metrics-timer-" + UUID.randomUUID());
            pd.setName("Test");
            pd.setVersion(1);
            pd.setSha256(UUID.randomUUID().toString());
            pd.setCreatedAt(Instant.now());
            processDefinitionRepository.save(pd);
            return pd.getId();
        });
        UUID pi = transactionTemplate.execute(status -> {
            ProcessInstanceEntity e = new ProcessInstanceEntity();
            e.setId(UUID.randomUUID());
            e.setProcessDefinitionId(pdId);
            e.setStartedAt(Instant.now());
            e.setCancelled(true);
            processInstanceRepository.save(e);
            return e.getId();
        });
        UUID jobId = transactionTemplate.execute(status -> {
            TimerJobEntity entity = new TimerJobEntity();
            entity.setId(UUID.randomUUID());
            entity.setActivityId(UUID.randomUUID());
            entity.setProcessInstanceId(pi);
            entity.setDueAt(Instant.now().minusSeconds(60));
            entity.setCreatedAt(Instant.now());
            entity.setFired(false);
            entity.setRemainingCount(1);
            entity.setExpression("R/PT1S");
            timerJobRepository.save(entity);
            return entity.getId();
        });
        TimerJob dto = transactionTemplate.execute(status -> {
            TimerJobEntity entity = timerJobRepository.findById(jobId).orElseThrow();
            TimerJob job = new TimerJob();
            job.setId(entity.getId());
            job.setActivityId(entity.getActivityId());
            job.setProcessInstanceId(entity.getProcessInstanceId());
            job.setDueAt(entity.getDueAt());
            job.setRemainingCount(entity.getRemainingCount());
            job.setBoundaryElementId(entity.getBoundaryElementId());
            job.setEventSubprocessId(entity.getEventSubprocessId());
            job.setExpression(entity.getExpression());
            return job;
        });
        long before = meterRegistry.find("zbpm.timer.lag").timer().count();

        try {
            timerJobExecutor.fire(dto);

            assertThat(meterRegistry.find("zbpm.timer.lag").timer().count() - before).isEqualTo(1);
        } finally {
            // No @Transactional here (Rel4-style setup commits via transactionTemplate) —
            // wipe our rows so classes sharing H2 never see them (pending listings count).
            transactionTemplate.executeWithoutResult(status -> {
                timerJobRepository.deleteById(jobId);
                processInstanceRepository.deleteById(pi);
                processDefinitionRepository.deleteById(pdId);
            });
        }
    }

    @Test
    void scriptTimeoutAndRejection_incrementThroughRealBulkhead() throws Exception {
        // Real ScriptServiceImpl bulkhead (no Spring): slow expressions trip the timeout
        // counter; a deterministically saturated pool trips the rejection counter.
        // ~1s of real FEEL work + instant overload (queueWait=0).
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        BpmMetrics metrics = new BpmMetrics(registry);
        com.zorrodev.bpm.engine.service.ScriptService service =
            new com.zorrodev.bpm.engine.service.impl.ScriptServiceImpl(
                new org.camunda.feel.impl.script.FeelUnaryTestsScriptEngineFactory().getScriptEngine(),
                new org.camunda.feel.impl.script.FeelScriptEngineFactory().getScriptEngine(),
                new tools.jackson.databind.ObjectMapper(), metrics, 1, 2, 10, 5);
        String slowExpr = "for i in 1..5000 return for j in 1..5000 return i * j";

        try {
            service.evaluateExpression(slowExpr, List.of());
        } catch (com.zorrodev.bpm.contract.exception.EngineException e) {
            // expected timeout
        }
        assertThat(registry.find("zbpm.script.timeout").counter().count()).isEqualTo(1.0);

        // WO-ENG-24: детерминированное насыщение вместо flood из 20 потоков.
        // Flood полагался на мгновенный AbortPolicy-отказ; с admission-wait
        // воркеры могли освободить слоты за время ожидания и rejected флапал
        // (0 вместо ≥1). Теперь: занимаем оба воркера + всю очередь
        // latch-задачами (факт — размер очереди, не сон) и шлём один overflow
        // на сервис с queueWait=0 (мгновенный сброс — та же ветка кода, что
        // flood ловил раньше, но без гонки). Тип — ScriptOverloadException
        // (наследник EngineException), счётчик rejected растёт через реальный
        // прод-путь submitToPool.
        BpmMetrics metrics2 = new BpmMetrics(registry);
        com.zorrodev.bpm.engine.service.impl.ScriptServiceImpl saturated =
            new com.zorrodev.bpm.engine.service.impl.ScriptServiceImpl(
                new org.camunda.feel.impl.script.FeelUnaryTestsScriptEngineFactory().getScriptEngine(),
                new org.camunda.feel.impl.script.FeelScriptEngineFactory().getScriptEngine(),
                new tools.jackson.databind.ObjectMapper(), metrics2, 10, 2, 1, 0);
        java.util.concurrent.CountDownLatch workersBusy =
            new java.util.concurrent.CountDownLatch(2); // оба воркера на латче
        java.util.concurrent.CountDownLatch release =
            new java.util.concurrent.CountDownLatch(1);
        Thread[] workers = new Thread[2];
        for (int i = 0; i < workers.length; i++) {
            workers[i] = new Thread(() -> {
                try {
                    saturated.runWithBudget(() -> {
                        workersBusy.countDown();
                        release.await(30, java.util.concurrent.TimeUnit.SECONDS);
                        return null;
                    }, "metric-wiring-holder");
                } catch (RuntimeException e) {
                    // timeout/interrupt on teardown — not the asserted path
                }
            });
            workers[i].setDaemon(true);
            workers[i].start();
        }
        Thread filler = null;
        try {
            assertThat(workersBusy.await(10, java.util.concurrent.TimeUnit.SECONDS))
                .as("pool (2) occupied").isTrue();
            java.lang.reflect.Field poolField =
                com.zorrodev.bpm.engine.service.impl.ScriptServiceImpl.class.getDeclaredField("executor");
            poolField.setAccessible(true);
            java.util.concurrent.ThreadPoolExecutor pool =
                (java.util.concurrent.ThreadPoolExecutor) poolField.get(saturated);
            // Filler — строго после barrier (иначе крадёт слот воркера);
            // в очереди (факт — размер, не сон: его тело не выполнится,
            // пока воркеры на латче).
            filler = new Thread(() -> {
                try {
                    saturated.runWithBudget(() -> {
                        release.await(30, java.util.concurrent.TimeUnit.SECONDS);
                        return null;
                    }, "metric-wiring-filler");
                } catch (RuntimeException e) {
                    // teardown — not the asserted path
                }
            });
            filler.setDaemon(true);
            filler.start();
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
                .until(() -> pool.getQueue().size() == 1);
            try {
                saturated.evaluateExpression("x + 1", List.of());
                assertThat(false).as("saturated pool must shed load").isTrue();
            } catch (com.zorrodev.bpm.engine.service.ScriptOverloadException e) {
                // expected overload — the asserted path
            }
        } finally {
            release.countDown();
            for (Thread t : workers) {
                t.join(15_000);
            }
            if (filler != null) {
                filler.join(15_000);
            }
            // Без shutdown(): потоки пула daemon ("script-eval"), JVM выходит
            // сама — как в старом flood-варианте этого же теста. shutdown()
            // здесь недоступен (package-private в service.impl).
        }
        assertThat(registry.find("zbpm.script.rejected").counter().count()).isGreaterThanOrEqualTo(1.0);
    }
}
