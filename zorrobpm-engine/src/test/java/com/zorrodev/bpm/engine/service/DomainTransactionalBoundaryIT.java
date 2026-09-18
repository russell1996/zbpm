package com.zorrodev.bpm.engine.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.handler.ElementSupport;
import com.zorrodev.bpm.engine.handler.FlowNavigator;
import com.zorrodev.bpm.engine.handler.TokenExecutor;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-REL-30: доменная транзакционная граница.
 *
 * <p>Класс намеренно БЕЗ {@code @Transactional} (P-18): иначе тестовая транзакция
 * скрыла бы отсутствие {@code @Transactional} на прод-методах — ровно тот дефект,
 * который чинит этот WO.
 *
 * <p>T1 (критерий 1): сбой посередине {@code startProcessInstance} (создание PI
 * прошло, дальше — несуществующий parent) обязан дать полный rollback, а не
 * висячий частично созданный инстанс.
 * T2 (критерий 2): {@code lockAndReload} — ровно один SELECT ... FOR UPDATE
 * (сегодня: 3 стейтмента — select, select-for-update, select).
 * T3 (критерий 3): {@code finishBranch} с пропавшим токеном — управляемая ветка
 * (log.warn + return), а не голый 500 через {@code NoSuchElementException}.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
class DomainTransactionalBoundaryIT {

    @Autowired ProcessDefinitionService processDefinitionService;
    @Autowired RuntimeService runtimeService;
    @Autowired ActivityService activityService;
    @Autowired ElementSupport elementSupport;
    @Autowired FlowNavigator flowNavigator;
    @Autowired BpmnService bpmnService;
    @Autowired ActivityRepository activityRepository;
    @Autowired ProcessInstanceRepository processInstanceRepository;
    @Autowired QueryService queryService;
    @Autowired com.zorrodev.bpm.engine.repository.DomainEventRepository domainEventRepository;
    @Autowired com.zorrodev.bpm.engine.repository.OutboxRepository outboxRepository;

    private UUID deployServiceTaskProcess() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/integration/process1.bpmn"));
        String randomKey = "reltx" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        // process id в фикстуре — Process_1lkt6gs; уникальный ключ на тест, иначе P-59 (общая H2).
        String keyed = bpmn.replace("Process_1lkt6gs", randomKey);
        ProcessDefinition model = processDefinitionService.addProcessDefinition(keyed);
        return model.getId();
    }

    private UUID activeServiceTaskActivityId(UUID processInstanceId) {
        List<ActivityEntity> active = activityRepository
            .findByProcessInstanceIdAndStatusIn(processInstanceId,
                List.of(ActivityStatus.CREATED, ActivityStatus.IN_PROGRESS));
        assertThat(active).as("ожидается запаркованная service task").isNotEmpty();
        return active.get(0).getId();
    }

    // ── T1: критерий 1 ──────────────────────────────────────────────
    // Сбой посередине старта: create проходит, а execute падает. Ломаем execute
    // несуществующим start-элементом: startProcessInstanceFromStartEvent делает
    // createProcessInstance и лишь потом execute(processInstanceId, tokenId,
    // startElementId), который бросает IllegalStateException на null-элементе
    // ПОСЛЕ create. Без доменной Tx create уже закоммичен своим автокоммитом →
    // висячий PI (count+1, RED). С доменной Tx (@Transactional на
    // ActivityServiceImpl) весь старт катится целиком → count неизменен (GREEN).

    @Test
    void startProcessInstance_failsAfterCreate_rollsBackFully() throws Exception {
        UUID pdId = deployServiceTaskProcess();
        long before = processInstanceRepository.count();

        assertThatThrownBy(() -> activityService.startProcessInstanceFromStartEvent(pdId, "no-such-element", List.of()))
            .as("старт с несуществующим элементом обязан упасть (падение в execute, после create)")
            .isInstanceOf(RuntimeException.class);

        assertThat(processInstanceRepository.count())
            .as("сбой посередине обязан дать полный rollback — висячих инстансов нет")
            .isEqualTo(before);
    }

    // ── T2: критерий 2 ──────────────────────────────────────────────

    @Test
    void lockAndReload_singleSelectForUpdate() throws Exception {
        UUID pdId = deployServiceTaskProcess();
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        dto.setVariables(List.of());
        UUID piId = runtimeService.startProcessInstance(dto).getId();
        UUID activityId = activeServiceTaskActivityId(piId);

        // lockAndReload без внешней Tx открывает свою (join-аннотация на
        // DBServiceImpl.getActivityForUpdate) — как любой прямой вызов домена.
        Logger sqlLogger = (Logger) LoggerFactory.getLogger("org.hibernate.SQL");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Level prev = sqlLogger.getLevel();
        sqlLogger.setLevel(Level.DEBUG);
        sqlLogger.addAppender(appender);
        try {
            elementSupport.lockAndReload(activityId);
        } finally {
            sqlLogger.detachAppender(appender);
            sqlLogger.setLevel(prev == null ? Level.INFO : prev);
        }

        List<String> activitySelects = appender.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .filter(m -> m.toLowerCase().contains("activit"))
            .toList();
        assertThat(activitySelects)
            .as("lockAndReload обязан читать activities ровно одним стейтментом, держащим FOR UPDATE")
            .hasSize(1);
        assertThat(activitySelects.get(0).toLowerCase())
            .as("единственный стейтмент обязан нести FOR UPDATE (атомарно лочит и PI)")
            .contains("for update");
    }

    // ── T3: критерий 3 ──────────────────────────────────────────────

    @Test
    void finishBranch_missingToken_managedBranchNot500() throws Exception {
        UUID pdId = deployServiceTaskProcess();
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        dto.setVariables(List.of());
        UUID piId = runtimeService.startProcessInstance(dto).getId();
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(pdId);
        TokenExecutor executor = Mockito.mock(TokenExecutor.class);

        assertThatCode(() -> flowNavigator.finishBranch(piId, UUID.randomUUID(), bpmn, executor))
            .as("пропавший токен — управляемая ветка, не исключение")
            .doesNotThrowAnyException();

        assertThat(queryService.getProcessInstance(piId).getCompletedAt())
            .as("fail-closed: инстанс не завершается по несуществующему токену")
            .isNull();
    }

    // ── T4: критерий 4, engine-нога happy path ───────────────────────

    @Test
    void runtimeStart_happyPath() throws Exception {
        UUID pdId = deployServiceTaskProcess();
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(pdId);
        dto.setVariables(List.of());
        UUID piId = runtimeService.startProcessInstance(dto).getId();

        assertThat(queryService.getProcessInstance(piId)).isNotNull();
        assertThat(queryService.getProcessInstance(piId).getCompletedAt()).isNull();
        // цепочка create→token→execute цела: service task запаркована и видна
        assertThat(activeServiceTaskActivityId(piId)).isNotNull();
    }
}
