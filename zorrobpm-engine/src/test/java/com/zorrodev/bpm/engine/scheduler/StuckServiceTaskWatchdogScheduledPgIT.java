package com.zorrodev.bpm.engine.scheduler;

import com.zorrodev.bpm.contract.dto.Incident;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import com.zorrodev.bpm.engine.service.db.IncidentDbOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REL-35: watchdog через РЕАЛЬНЫЙ {@code @Scheduled}-путь.
 *
 * <p>Каждый тест вызывает {@code watchdog.checkStuckTasks()} через Spring-wiring —
 * без ручной {@code TransactionTemplate}-обёртки (та обёртка и маскировала F08:
 * внешняя Tx прятала отсутствие внутренней). P-18 инверсия здесь неуместна:
 * предмет проверки — именно транзакционное поведение прод-кода, а не его
 * отсутствие в тесте.
 *
 * <p>T1 (критерий 1, F08): fault injection на 2-м {@code createIncident} —
 * без единой Tx первый инцидент уже закоммичен (RED), с единой Tx весь батч
 * катится (GREEN).
 * T2 (критерий 2, F09): 101 застрявшая задача, первые 100 с открытым инцидентом —
 * 101-я обязана получить инцидент тем же проходом (NOT EXISTS до LIMIT).
 * T3 (сторож батчинга): 25 задач при batch=10 — три прохода дают 10+10+5.
 */
@Tag("pg")
@Import(StuckServiceTaskWatchdogScheduledPgIT.FaultConfig.class)
public class StuckServiceTaskWatchdogScheduledPgIT extends PostgresIT {

    /**
     * Fault injection поверх реального бина: считает {@code createIncident},
     * по флагу бросает на N-м вызове. Делегат — настоящий
     * {@code incidentDbOperationsImpl}, остальное поведение продовое.
     */
    @TestConfiguration
    static class FaultConfig {
        static final AtomicBoolean faultEnabled = new AtomicBoolean(false);
        static final AtomicInteger createCount = new AtomicInteger(0);

        @Bean
        @Primary
        IncidentDbOperations faultingIncidentDbOperations(
                @Qualifier("incidentDbOperationsImpl") IncidentDbOperations delegate) {
            return new IncidentDbOperations() {
                @Override
                public UUID createIncident(UUID activityId, String message) {
                    if (faultEnabled.get() && createCount.incrementAndGet() == 2) {
                        throw new RuntimeException("REL35-fault-injection after first incident");
                    }
                    return delegate.createIncident(activityId, message);
                }

                @Override
                public Incident getIncident(UUID incidentId) {
                    return delegate.getIncident(incidentId);
                }

                @Override
                public void completeIncident(UUID incidentId) {
                    delegate.completeIncident(incidentId);
                }

                @Override
                public void completeIncidentsByActivityIds(List<UUID> activityIds) {
                    delegate.completeIncidentsByActivityIds(activityIds);
                }

                @Override
                public List<Incident> findOpenIncidentsByActivityIds(List<UUID> activityIds) {
                    return delegate.findOpenIncidentsByActivityIds(activityIds);
                }
            };
        }
    }

    @Autowired ProcessDefinitionService processDefinitionService;
    @Autowired RuntimeService runtimeService;
    @Autowired ActivityRepository activityRepository;
    @Autowired IncidentRepository incidentRepository;
    @Autowired IncidentDbOperations incidentDbOperations;
    @Autowired StuckServiceTaskWatchdog watchdog;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JdbcTemplate jdbcTemplate;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        FaultConfig.faultEnabled.set(false);
        FaultConfig.createCount.set(0);
        watchdog.setDispatchTimeout(Duration.ofMinutes(5));
        watchdog.setBatchSize(100);
        tx.executeWithoutResult(s -> {
            jdbcTemplate.update("DELETE FROM incidents");
            jdbcTemplate.update("UPDATE activities SET created_at = now() WHERE type = 'SERVICE_TASK' AND status = 'CREATED'");
        });
    }

    private UUID deployOnce() {
        return tx.execute(s -> {
            try {
                String bpmn = Files.readString(Paths.get("src/test/files/test3.bpmn"));
                ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
                return model.getId();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** Стартует инстанс и возвращает id его CREATED serviceTask-активности. */
    private UUID startStuckCandidate(UUID pdId) {
        UUID pi = tx.execute(s -> {
            StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
            dto.setProcessDefinitionId(pdId);
            return runtimeService.startProcessInstance(dto).getId();
        });
        return tx.execute(s -> activityRepository.findAll().stream()
                .filter(a -> a.getProcessInstanceId().equals(pi)
                        && a.getBpmnElementId().equals("serviceTask1")
                        && a.getStatus() == ActivityStatus.CREATED)
                .map(a -> a.getId())
                .findFirst().orElseThrow());
    }

    /**
     * Сдвигает created_at строго по возрастанию (row i старше row i+1 на 1с):
     * ORDER BY created_at детерминирован, tie-порядок исключён. Все строки
     * остаются старше timeout (base 10m, +100с max) — F09-тесты детерминированы.
     */
    private void shiftCreatedAt(List<UUID> activityIds, Duration baseAgo) {
        Instant base = Instant.now().minus(baseAgo);
        tx.executeWithoutResult(s -> {
            for (int i = 0; i < activityIds.size(); i++) {
                jdbcTemplate.update("UPDATE activities SET created_at = ? WHERE id = ?",
                        Timestamp.from(base.plusSeconds(i)), activityIds.get(i));
            }
        });
    }

    private long countOpenIncidents(UUID activityId) {
        return tx.execute(s -> (long) incidentRepository
                .findByActivityIdInAndCompletedAtIsNull(List.of(activityId)).size());
    }

    private long countAllOpenIncidents() {
        return tx.execute(s -> jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM incidents WHERE completed_at IS NULL", Long.class));
    }

    // ── T1: критерий 1 (F08) ────────────────────────────────────────

    @Test
    void scheduledPath_midBatchFault_rollsBackWholeBatch() {
        UUID pdId = deployOnce();
        UUID first = startStuckCandidate(pdId);
        UUID second = startStuckCandidate(pdId);
        shiftCreatedAt(List.of(first, second), Duration.ofMinutes(10));

        FaultConfig.faultEnabled.set(true);
        try {
            // РЕАЛЬНЫЙ путь: без внешней Tx. checkStuckTasks ловит исключение сам —
            // assert по состоянию, а не по пробросу.
            watchdog.checkStuckTasks();
        } finally {
            FaultConfig.faultEnabled.set(false);
        }

        assertThat(countOpenIncidents(first))
                .as("F08: сбой посередине батча обязан катить ВЕСЬ батч — первый инцидент не переживает rollback")
                .isEqualTo(0L);
        assertThat(countOpenIncidents(second))
                .as("F08: второй инцидент не создан (fault)")
                .isEqualTo(0L);
    }

    // ── T2: критерий 2 (F09) ────────────────────────────────────────

    @Test
    void scheduledPath_firstPageIncidented_lastTaskStillGetsIncident() {
        watchdog.setBatchSize(100);
        UUID pdId = deployOnce();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            ids.add(startStuckCandidate(pdId));
        }
        // 101-я — самая новая: на старом коде она гарантированно за LIMIT-страницей.
        shiftCreatedAt(ids, Duration.ofMinutes(10));
        for (UUID id : ids.subList(0, 100)) {
            final UUID aid = id;
            tx.executeWithoutResult(s -> incidentDbOperations.createIncident(aid, "pre-existing open incident"));
        }
        UUID last = ids.get(100);
        assertThat(countOpenIncidents(last)).isEqualTo(0L);

        // РЕАЛЬНЫЙ путь: один проход.
        watchdog.checkStuckTasks();

        assertThat(countOpenIncidents(last))
                .as("F09: 101-я задача обязана получить инцидент — NOT EXISTS до LIMIT, а не Java-фильтр после")
                .isEqualTo(1L);
    }

    // ── T3: сторож батчинга ─────────────────────────────────────────

    @Test
    void scheduledPath_largeFanOut_processedInBatches() {
        watchdog.setBatchSize(10);
        UUID pdId = deployOnce();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            ids.add(startStuckCandidate(pdId));
        }
        shiftCreatedAt(ids, Duration.ofMinutes(10));

        watchdog.checkStuckTasks();
        assertThat(countAllOpenIncidents()).as("проход 1: первый батч 10").isEqualTo(10L);
        watchdog.checkStuckTasks();
        assertThat(countAllOpenIncidents()).as("проход 2: второй батч 10").isEqualTo(20L);
        watchdog.checkStuckTasks();
        assertThat(countAllOpenIncidents()).as("проход 3: хвост 5").isEqualTo(25L);
    }
}
