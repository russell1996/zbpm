package com.zorrodev.bpm.engine.handler;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import com.zorrodev.bpm.exchange.ServiceTaskDispatchPhase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-36 (CR-01, крит.3): конкурентная доставка дубликатов + повторная доставка
 * после "перезапуска" дают РОВНО ОДИН сохранённый переход на PG.
 *
 * <p>Только реальный PostgreSQL: конкурентные транзакции сериализуются построчными
 * локами {@code lockInstanceFirst} (instance→activity) — H2 такой семантики не даёт.
 * Каждый раунд — свежий инстанс (окно гонки одноразовое: первая completion закрывает
 * фазу, дубликаты обязаны глохнуть).
 *
 * <p>POF: мутация «phased-путь делегирует в legacy-цепочку без exact-match»
 * даёт 2 endEvent-строки (второй дубликат падает в хвост) — тест RED.
 */
@Tag("pg")
class ListenerDuplicateCompletionPgIT extends PostgresIT {

    private static final int ROUNDS = 5;
    private static final int DUPLICATES = 3;

    @Autowired ProcessDefinitionService processDefinitionService;
    @Autowired RuntimeService runtimeService;
    @Autowired QueryService queryService;
    @Autowired ActivityRepository activityRepository;
    @Autowired PlatformTransactionManager txManager;

    private UUID deployOnce() throws Exception {
        String xml = Files.readString(Paths.get("src/test/files/test-c8-execution-listeners.bpmn"));
        // Минус end-listener — изолируем START-фазу; уникальный id процесса.
        xml = xml.replace("          <zeebe:executionListener eventType=\"end\" type=\"listener-job\" />\n", "")
            .replace("c8-exec-listeners", "c8dup-pg-" + UUID.randomUUID().toString().substring(0, 8));
        return processDefinitionService.addProcessDefinition(xml).getId();
    }

    private UUID start(UUID definitionId) {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(definitionId);
        return runtimeService.startProcessInstance(dto).getId();
    }

    private UUID svcId(UUID pi) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi) && a.getBpmnElementId().equals("svc"))
            .map(a -> a.getId())
            .findFirst().orElseThrow();
    }

    private long count(UUID pi, String element, ActivityStatus status) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi)
                && a.getBpmnElementId().equals(element)
                && a.getStatus() == status)
            .count();
    }

    @Test
    void concurrentDuplicatesAndPostCompletionRedelivery_singleTransition() throws Exception {
        UUID definitionId = deployOnce();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        ExecutorService pool = Executors.newFixedThreadPool(DUPLICATES);
        try {
            UUID lastPi = null;
            UUID lastSvc = null;
            for (int round = 0; round < ROUNDS; round++) {
                UUID piId = start(definitionId);
                UUID activityId = svcId(piId);
                // Первый SUCCESS слушателя — фаза закрыта, реальное задание в полёте.
                tx.executeWithoutResult(s -> runtimeService.completeServiceTask(activityId, List.of(),
                    ServiceTaskDispatchPhase.START, 0));

                // DUPLICATES конкурентных дубликатов ТОГО ЖЕ вызова, в lockstep.
                CyclicBarrier gate = new CyclicBarrier(DUPLICATES);
                List<Future<Object>> futures = java.util.stream.IntStream.range(0, DUPLICATES)
                    .mapToObj(i -> pool.submit(() -> {
                        try {
                            gate.await(10, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                        tx.executeWithoutResult(s -> runtimeService.completeServiceTask(activityId, List.of(),
                            ServiceTaskDispatchPhase.START, 0));
                        return null;
                    }))
                    .toList();
                for (Future<Object> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }

                // Результат реального задания — единственный переход.
                tx.executeWithoutResult(s -> runtimeService.completeServiceTask(activityId, List.of(),
                    ServiceTaskDispatchPhase.REAL, null));

                assertThat(count(piId, "svc", ActivityStatus.COMPLETED))
                    .as("раунд %s: ровно одна COMPLETED-строка svc", round)
                    .isEqualTo(1);
                assertThat(count(piId, "endEvent", ActivityStatus.COMPLETED))
                    .as("раунд %s: ровно один сохранённый переход (endEvent)", round)
                    .isEqualTo(1);
                assertThat(queryService.getProcessInstance(piId).getCompletedAt())
                    .as("раунд %s: инстанс завершён", round)
                    .isNotNull();
                lastPi = piId;
                lastSvc = activityId;
            }

            // "Перезапуск" (эмуляция): движок пережил рестарт воркера, брокер
            // переотдал оба completion'а — оба обязаны игнориться (инстанс уже done).
            UUID piDone = lastPi;
            UUID svcDone = lastSvc;
            tx.executeWithoutResult(s -> runtimeService.completeServiceTask(svcDone, List.of(),
                ServiceTaskDispatchPhase.START, 0));
            tx.executeWithoutResult(s -> runtimeService.completeServiceTask(svcDone, List.of(),
                ServiceTaskDispatchPhase.REAL, null));
            assertThat(count(piDone, "endEvent", ActivityStatus.COMPLETED))
                .as("повторная доставка после завершения: переходов по-прежнему ровно один")
                .isEqualTo(1);
            assertThat(queryService.getProcessInstance(piDone).getCompletedAt()).isNotNull();
        } finally {
            pool.shutdownNow();
        }
    }
}
