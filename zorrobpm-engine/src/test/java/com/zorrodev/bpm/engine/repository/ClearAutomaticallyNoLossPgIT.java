package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-AUDIT-8 (NEW2-17, watch): условный UPDATE с {@code clearAutomatically}
 * НЕ теряет неснятые изменения вызывающего — на живом PostgreSQL.
 *
 * <p>Механика подозрения: {@code setStatusAndCompletedAtIfStatusIn} —
 * {@code @Modifying(clearAutomatically=true, flushAutomatically=true)}.
 * {@code flushAutomatically} сбрасывает pending-изменения вызывающего в БД ДО
 * bulk-UPDATE (потери нет), {@code clearAutomatically} вычищает persistence
 * context ПОСЛЕ (отсоединённые сущности — ожидаемо, не дефект).
 *
 * <p>Тест: внутри одной транзакции меняем поле сущности (save, без flush),
 * затем вызываем условный UPDATE — проверяем, что (а) UPDATE применился и
 * (б) неснятое изменение ТОЖЕ в БД (flush сработал до bulk). POF-мутации
 * (обе — в ПРОД-аннотации, на живой PG):
 * (1) убрать {@code flushAutomatically=true} → тест ОСТАЁТСЯ ЗЕЛЁНЫМ — и это
 * НЕ вакуумность harness'а, а факт фреймворка: Hibernate в дефолтном
 * FlushMode.AUTO сбрасывает dirty-изменения перед bulk-JPQL сам, явный flush
 * здесь избыточен (но безвреден — защищает при смене flush-режима, поэтому
 * прод-код НЕ трогаем, V7);
 * (2) убрать {@code AND e.status IN :allowed} → КРАСНЫЙ
 * ({@code expected: 0 but was: 1} в {@code refusesTerminalRow}): harness реально
 * гоняет прод-аннотацию и чувствителен к её условию.
 * Итог NEW2-17: дефекта потери нет (доказательство — GREEN + мутация 1 с
 * объяснённой причиной + мутация 2 как контроль чувствительности).
 */
@Tag("pg")
class ClearAutomaticallyNoLossPgIT extends PostgresIT {

    @Autowired private ActivityRepository activityRepository;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;

    /**
     * Живой инстанс — для FK {@code fk_activities__process_instance_id} и
     * {@code fk_activities__token}: probe-строки вяжутся к реально
     * стартовавшему процессу и его живому токену (живой BPMN-путь, прецедент
     * {@code DomainTransactionalBoundaryPgIT}), а не к случайным UUID.
     */
    private record LiveScope(UUID piId, UUID token) {}

    private LiveScope liveScope() {
        return new TransactionTemplate(txManager).execute(s -> {
            try {
                String bpmn = Files.readString(Paths.get("src/test/files/integration/process1.bpmn"));
                String randomKey = "audit8probe" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                String keyed = bpmn.replace("Process_1lkt6gs", randomKey);
                ProcessDefinition model = processDefinitionService.addProcessDefinition(keyed);
                StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
                dto.setProcessDefinitionId(model.getId());
                dto.setVariables(List.of());
                UUID piId = runtimeService.startProcessInstance(dto).getId();
                UUID token = activityRepository.findAll().stream()
                    .filter(a -> a.getProcessInstanceId().equals(piId))
                    .map(ActivityEntity::getToken).findFirst()
                    .orElseThrow(() -> new IllegalStateException("no live activity/token for " + piId));
                return new LiveScope(piId, token);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void conditionalUpdate_preservesUnflushedCallerChanges() {
        UUID id = UUID.randomUUID();
        LiveScope scope = liveScope();
        UUID piId = scope.piId();
        Instant now = Instant.now();

        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            ActivityEntity e = new ActivityEntity();
            e.setId(id);
            e.setProcessInstanceId(piId);
            e.setToken(scope.token());
            e.setBpmnElementId("probe");
            e.setStatus(ActivityStatus.CREATED);
            e.setCreatedAt(Instant.now());
            e.setType(com.zorrodev.bpm.engine.bpmn.model.BpmnElementType.SERVICE_TASK);
            activityRepository.save(e);

            // Неснятое изменение вызывающего: меняем поле БЕЗ save/flush.
            ActivityEntity managed = activityRepository.findById(id).orElseThrow();
            managed.setBpmnElementId("probe-renamed");

            // Условный bulk-UPDATE с clearAutomatically+flushAutomatically.
            int updated = activityRepository.setStatusAndCompletedAtIfStatusIn(
                id, ActivityStatus.ERROR, now,
                List.of(ActivityStatus.CREATED, ActivityStatus.IN_PROGRESS));
            assertThat(updated).as("условный UPDATE применился").isEqualTo(1);

            // После clear контекст вычищен — читаем СВЕЖИМ запросом (новая
            // транзакция видит только закоммиченное; здесь — тот же снапшот
            // через clear+find: flush ДО bulk обязан был сохранить rename).
            activityRepository.findById(id).orElseThrow();
        });

        ActivityEntity reloaded = activityRepository.findById(id).orElseThrow();
        assertThat(reloaded.getStatus())
            .as("статус из условного UPDATE — в БД")
            .isEqualTo(ActivityStatus.ERROR);
        assertThat(reloaded.getBpmnElementId())
            .as("неснятое изменение вызывающего НЕ потеряно bulk-UPDATE "
                + "(flushAutomatically сработал до него)")
            .isEqualTo("probe-renamed");
    }

    @Test
    void conditionalUpdate_refusesTerminalRow() {
        UUID id = UUID.randomUUID();
        LiveScope scope = liveScope();
        UUID piId = scope.piId();
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            ActivityEntity e = new ActivityEntity();
            e.setId(id);
            e.setProcessInstanceId(piId);
            e.setToken(scope.token());
            e.setBpmnElementId("probe2");
            e.setStatus(ActivityStatus.COMPLETED);
            e.setCreatedAt(Instant.now());
            e.setType(com.zorrodev.bpm.engine.bpmn.model.BpmnElementType.SERVICE_TASK);
            activityRepository.save(e);

            int updated = activityRepository.setStatusAndCompletedAtIfStatusIn(
                id, ActivityStatus.ERROR, Instant.now(),
                List.of(ActivityStatus.CREATED, ActivityStatus.IN_PROGRESS));
            assertThat(updated)
                .as("терминальная строка НЕ переворачивается в ERROR (защита ENG-23)")
                .isEqualTo(0);
        });
        assertThat(activityRepository.findById(id).orElseThrow().getStatus())
            .isEqualTo(ActivityStatus.COMPLETED);
    }
}
