package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.handler.ElementSupport;
import com.zorrodev.bpm.engine.handler.FlowNavigator;
import com.zorrodev.bpm.engine.handler.TokenExecutor;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-REL-30: та же доменная граница, но на реальном PostgreSQL (V11/G-N).
 *
 * <p>H2 молча проглатывает то, что PG отвергает (FOR UPDATE вне Tx, FK, типы):
 * T1 (rollback посередине старта) и T3 (пропавший токен) обязаны быть зелёными
 * именно на PG. T2 (подсчёт SQL-стейтментов через H2-логгер) здесь не дублируется —
 * число стейтментов диалекто-независимо, а сам FOR UPDATE на PG доказан уже тем,
 * что T1/T3 и весь PG-набор идут через {@code findByIdForUpdate} без
 * {@code TransactionRequiredException}. Класс намеренно БЕЗ {@code @Transactional} (P-18).
 */
@Tag("pg")
class DomainTransactionalBoundaryPgIT extends PostgresIT {

    @Autowired ProcessDefinitionService processDefinitionService;
    @Autowired RuntimeService runtimeService;
    @Autowired ActivityService activityService;
    @Autowired ElementSupport elementSupport;
    @Autowired FlowNavigator flowNavigator;
    @Autowired BpmnService bpmnService;
    @Autowired ActivityRepository activityRepository;
    @Autowired ProcessInstanceRepository processInstanceRepository;
    @Autowired QueryService queryService;
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;

    private UUID deployServiceTaskProcess() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/integration/process1.bpmn"));
        String randomKey = "reltxpg" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String keyed = bpmn.replace("Process_1lkt6gs", randomKey);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(keyed);
        return model.getId();
    }

    @Test
    void startProcessInstance_failsAfterCreate_rollsBackFully() throws Exception {
        UUID pdId = deployServiceTaskProcess();
        long before = processInstanceRepository.count();

        assertThatThrownBy(() -> activityService.startProcessInstanceFromStartEvent(pdId, "no-such-element", List.of()))
            .as("старт с несуществующим элементом обязан упасть (падение в execute, после create)")
            .isInstanceOf(RuntimeException.class);

        assertThat(processInstanceRepository.count())
            .as("PG: сбой посередине обязан дать полный rollback — висячих инстансов нет")
            .isEqualTo(before);
    }

    @Test
    void finishBranch_missingToken_managedBranchNot500() throws Exception {
        UUID pdId = deployServiceTaskProcess();
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        dto.setVariables(List.of());
        UUID piId = runtimeService.startProcessInstance(dto).getId();
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(pdId);

        assertThatCode(() -> flowNavigator.finishBranch(piId, UUID.randomUUID(), bpmn, Mockito.mock(TokenExecutor.class)))
            .as("PG: пропавший токен — управляемая ветка, не исключение")
            .doesNotThrowAnyException();

        assertThat(queryService.getProcessInstance(piId).getCompletedAt())
            .as("PG: fail-closed — инстанс не завершается по несуществующему токену")
            .isNull();
    }

    /**
     * WO-REL-30 (B-3) + WO-REL-59/63 на реальном PostgreSQL: доменный захват
     * берёт instance-lock ПЕРВЫМ, потом activity-lock, и оба FOR UPDATE
     * работают на PG.
     *
     * <p>WO-REL-63 удалил {@code lockAndReload} (activity-only) — после перевода
     * последних путей у него не осталось продакшн-вызовов, и он был ровно той
     * ловушкой, которой ловился ABBA-дедлок с отменой. Тест переведён на
     * {@code lockInstanceFirst} и проверяет более сильное утверждение, чем
     * прежний «просто вернул не-null»: ПОРЯДОК захватов виден в логе SQL.
     *
     * <p>Честная граница изменения: составной захват требует внешней транзакции
     * ({@code getActivity} и {@code lockProcessInstance} не открывают свои —
     * JOIN-аннотация стоит на {@code getActivityForUpdate}), поэтому вызов идёт
     * в {@code TransactionTemplate}. Все живые вызывающие — доменные методы с
     * классовым {@code @Transactional} либо {@code TimerJobExecutor.fire} с
     * {@code REQUIRES_NEW}, так что в бою условие выполняется; прежняя проверка
     * «голый доменный вызов сам открывает транзакцию» к составному захвату уже
     * неприменима и заменена проверкой порядка.
     */
    @Test
    void lockInstanceFirst_instanceLockBeforeActivityLockOnPg() throws Exception {
        UUID pdId = deployServiceTaskProcess();
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        dto.setVariables(List.of());
        UUID piId = runtimeService.startProcessInstance(dto).getId();
        List<ActivityEntity> active = activityRepository.findByProcessInstanceIdAndStatusIn(
            piId, List.of(ActivityStatus.CREATED, ActivityStatus.IN_PROGRESS));
        assertThat(active).as("PG: запаркованная service task").isNotEmpty();
        UUID activityId = active.get(0).getId();

        ch.qos.logback.classic.Logger sqlLogger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.hibernate.SQL");
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
            new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        var prev = sqlLogger.getLevel();
        sqlLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        sqlLogger.addAppender(appender);
        Object locked;
        try {
            locked = new org.springframework.transaction.support.TransactionTemplate(txManager)
                .execute(status -> elementSupport.lockInstanceFirst(activityId));
        } finally {
            sqlLogger.detachAppender(appender);
            sqlLogger.setLevel(prev == null ? ch.qos.logback.classic.Level.INFO : prev);
        }
        assertThat(locked).as("PG: составной доменный захват возвращает активность").isNotNull();

        List<String> sql = appender.list.stream()
            .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
            .toList();
        // Фильтр по таблице в FROM, а не по подстроке: колонка
        // parent_activity_id тоже содержит "activit". PESSIMISTIC_WRITE на PG
        // рендерится как FOR NO KEY UPDATE (H2 — как FOR UPDATE), поэтому
        // ловим обе формы.
        List<String> instanceLocks = sql.stream()
            .filter(m -> m.toLowerCase().contains("from process_instances") && isLocking(m))
            .toList();
        List<String> activityLocks = sql.stream()
            .filter(m -> m.toLowerCase().contains("from activities") && isLocking(m))
            .toList();
        assertThat(instanceLocks).as("PG: ровно один FOR UPDATE на process_instances").hasSize(1);
        assertThat(activityLocks).as("PG: ровно один FOR UPDATE на activities").hasSize(1);
        assertThat(sql.indexOf(instanceLocks.get(0)))
            .as("PG: instance-lock ПЕРЕД activity-lock — единственный порядок без ABBA с отменой")
            .isLessThan(sql.indexOf(activityLocks.get(0)));
    }

    /** PostgreSQL renders PESSIMISTIC_WRITE as FOR NO KEY UPDATE; H2 as FOR UPDATE. */
    private static boolean isLocking(String sql) {
        String s = sql.toLowerCase();
        return s.contains("for update") || s.contains("for no key update");
    }

    @Test
    void runtimeStart_happyPathOnPg() throws Exception {
        UUID pdId = deployServiceTaskProcess();
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        dto.setVariables(List.of());
        UUID piId = runtimeService.startProcessInstance(dto).getId();

        assertThat(queryService.getProcessInstance(piId)).isNotNull();
        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();
    }
}
