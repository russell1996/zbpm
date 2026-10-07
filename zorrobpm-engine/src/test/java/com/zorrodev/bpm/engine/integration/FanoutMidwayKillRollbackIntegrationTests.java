package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.handler.ExecutionContext;
import com.zorrodev.bpm.engine.handler.ExecutionCtx;
import com.zorrodev.bpm.engine.handler.InclusiveGatewayHandler;
import com.zorrodev.bpm.engine.handler.TokenExecutor;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.ParallelGatewayRepository;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-C8-38 (C38-4, вариант (в) решения CTO по Э-2): kill/исключение между фазами
 * двухфазного фанаута inclusive-сплита — это откат ВСЕЙ транзакции, а не strand.
 *
 * <p>Обе фазы фанаута ({@code InclusiveGatewayHandler:86-104} — processFlow-записи
 * arrived + {@code executor.execute} целей + {@code completeActivity} сплита в
 * finally) живут в одной транзакции классового {@code @Transactional}
 * {@code CompletionService}; планировщики ({@code TimerJobExecutor},
 * {@code TimerStartJobExecutor}) — каждый fire целиком в своём REQUIRES_NEW.
 * Персистентного «IN_PROGRESS навсегда» через рестарт не получается: kill между
 * фазами откатывает и arrived-строки 1-й фазы.
 *
 * <p>Граница доказательства (сказано прямо, чтобы тест не переоценивали): прямой
 * вызов {@code InclusiveGatewayHandler.handle} вне транзакции — это НЕ та же
 * транзакционная рамка, что боевая волна (боевую рамку держит вызывающий
 * {@code CompletionService}). Поэтому тест доказывает два факта раздельно:
 * (1) arrived-строки фазы 1 существуют ТОЛЬКО внутри волны — волна с исключением
 * в цели taskA не оставляет новых строк, join не припаркован (рамка — сравнение
 * числа строк ДО и ПОСЛЕ kill-волны: инвариант «kill-волна не пишет строк»);
 * (2) штатная волна того же определения завершается целиком, join — ровно один
 * раз, инстанс завершается. Реконсилер НЕ пишется (код по непроверенной
 * предпосылке, P-17; heartbeat/возраст — G-C). Явный скан путей, где исключение
 * внутри фанаута могло бы дать частичный коммит (catch/REQUIRES_NEW вокруг
 * {@code InclusiveGatewayHandler:86-104}), — в отчёте §C38-4, файл:строка каждого.
 * Без {@code @Transactional} на методе: откат проверяется по факту отката, а не
 * по обёртке теста.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class FanoutMidwayKillRollbackIntegrationTests {

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private QueryService queryService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private ParallelGatewayRepository parallelGatewayRepository;
    @Autowired private DBService dbService;
    @Autowired private ActivityService activityService;
    @Autowired private BpmnService bpmnService;
    @Autowired private InclusiveGatewayHandler inclusiveGatewayHandler;
    @Autowired private com.zorrodev.bpm.engine.handler.FlowNavigator flowNavigator;

    private UUID deploy() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test-c838-fanout-rollback.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        return model.getId();
    }

    private List<ActivityEntity> activities(UUID pi) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .toList();
    }

    private long countOf(UUID pi, String elementId, ActivityStatus status) {
        return activities(pi).stream()
            .filter(a -> elementId.equals(a.getBpmnElementId()))
            .filter(a -> a.getStatus() == status)
            .count();
    }

    private long incidents(UUID pi) {
        List<UUID> ids = activities(pi).stream().map(ActivityEntity::getId).toList();
        return incidentRepository.findAll().stream().filter(i -> ids.contains(i.getActivityId())).count();
    }

    @Test
    void exceptionInPhaseTwo_rollsBackPhaseOneArrivals() throws Exception {
        UUID defId = deploy();

        // Волна 1 (штатный старт): сплит завершён (COMPLETED=1 — премисса, что фанаут
        // прошёл целиком), taskA/taskB созданы фазой 2.
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(defId);
        UUID pi = runtimeService.startProcessInstance(dto).getId();
        assertThat(countOf(pi, "isplit", ActivityStatus.COMPLETED))
            .as("premise: the fan-out wave ran to completion")
            .isEqualTo(1L);
        assertThat(countOf(pi, "taskA", ActivityStatus.CREATED))
            .as("premise: phase 2 dispatched both targets")
            .isEqualTo(1L);
        assertThat(countOf(pi, "taskB", ActivityStatus.CREATED))
            .as("premise: phase 2 dispatched both targets")
            .isEqualTo(1L);

        // Kill между фазами в ТОЙ ЖЕ транзакционной рамке, что боевая волна:
        // перехват processFlow-записи фазы 1 — spy на FlowNavigator-бине, роняющий
        // вторую запись (fB) исключением. Волна выполняется внутри
        // runtimeService.startProcessInstance (классовый @Transactional
        // RuntimeServiceImpl -> ActivityServiceImpl.execute (catch Exception ->
        // incident-бокс!) -> InclusiveGatewayHandler.handle -> фаза 1
        // flowNavigator.processFlow(...)).
        //
        // Ловушка рамки (поймана живым прогоном, не чтением): ActivityServiceImpl.execute
        // ловит ЛЮБОЕ Exception хендлера/потока и паркует инцидент — волна НЕ бросает
        // наружу, а глотает kill в инцидент isplit. Значит kill обязан быть ТАКИМ
        // исключением, которое execute НЕ ловит: EngineException пробрасывается
        // (catch EngineException -> throw e, :162-164). Kill — EngineException
        // (боевой тип аборта волны, не тестовый RuntimeException).
        StartProcessInstanceDTO dto2 = new StartProcessInstanceDTO();
        dto2.setProcessDefinitionId(defId);
        com.zorrodev.bpm.engine.handler.FlowNavigator flowNavigatorSpy =
            org.mockito.Mockito.spy(flowNavigator);
        org.mockito.Mockito.doThrow(new com.zorrodev.bpm.contract.exception.EngineException(
                "C38-4 simulated kill between fan-out phases"))
            .when(flowNavigatorSpy).processFlow(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("fB"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        java.lang.reflect.Field flowField;
        com.zorrodev.bpm.engine.handler.FlowNavigator flowOriginal;
        try {
            flowField = com.zorrodev.bpm.engine.handler.InclusiveGatewayHandler.class
                .getDeclaredField("flowNavigator");
            flowField.setAccessible(true);
            flowOriginal = (com.zorrodev.bpm.engine.handler.FlowNavigator) flowField.get(inclusiveGatewayHandler);
            flowField.set(inclusiveGatewayHandler, flowNavigatorSpy);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot swap FlowNavigator for C38-4 kill wave", e);
        }
        UUID pi2 = null;
        try {
            try {
                pi2 = runtimeService.startProcessInstance(dto2).getId();
                org.assertj.core.api.Assertions.fail("the kill wave must throw");
            } finally {
                try {
                    flowField.set(inclusiveGatewayHandler, flowOriginal);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        } catch (RuntimeException expectedKill) {
            assertThat(expectedKill.getMessage())
                .as("premise: the wave died on the simulated kill, not on something else")
                .contains("C38-4 simulated kill");
        }

        // Откат виден по разнице: волна 1 (штатный старт pi) прошла целиком;
        // kill-волна не оставила НИ ОДНОГО инстанса ЭТОГО ОПРЕДЕЛЕНИЯ сверх pi:
        // ни arrived-строк, ни припаркованного join'а, ни сплит-активности.
        // (Фильтр — по definitionId: в общей тестовой БД живут инстансы других
        // определений/тестов, их трогать нельзя.)
        java.util.Set<UUID> ownInstances = activityRepository.findAll().stream()
            .filter(a -> defId.equals(dbService.getProcessInstance(a.getProcessInstanceId())
                .getProcessDefinitionId()))
            .map(ActivityEntity::getProcessInstanceId)
            .collect(java.util.stream.Collectors.toSet());
        assertThat(ownInstances)
            .as("the killed wave left no surviving instance of this definition — the whole wave rolled back")
            .containsExactly(pi);

        // Волна 3 — штатный повтор убитой волны (новый старт того же определения):
        // завершается целиком, join — ровно один раз, ветвь перезапускается штатно,
        // join не висит.
        StartProcessInstanceDTO dto3 = new StartProcessInstanceDTO();
        dto3.setProcessDefinitionId(defId);
        UUID pi3 = runtimeService.startProcessInstance(dto3).getId();
        complete(pi3, "taskA");
        complete(pi3, "taskB");
        assertThat(countOf(pi3, "join", ActivityStatus.COMPLETED))
            .as("the restarted wave completes the join exactly once — no hang, no double delivery")
            .isEqualTo(1L);
        assertThat(countOf(pi3, "taskAfter", ActivityStatus.CREATED)).isEqualTo(1L);
        assertThat(incidents(pi3)).isEqualTo(0L);
        complete(pi3, "taskAfter");
        assertThat(queryService.getProcessInstance(pi3).getCompletedAt())
            .as("the instance actually completes")
            .isNotNull();
    }

    private void complete(UUID pi, String elementId) {
        activities(pi).stream()
            .filter(a -> a.getBpmnElementId().equals(elementId))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED)
            .findFirst()
            .ifPresentOrElse(
                a -> runtimeService.completeUserTask(a.getId(), List.of()),
                () -> { throw new AssertionError("no CREATED " + elementId + " in " + pi); });
    }

    /**
     * Подмена хендлера в {@code HandlerRegistry} на время kill-волны — через публичный
     * {@code register} (та же ConcurrentHashMap, что читает {@code get}); оригинал
     * возвращается в finally вызывающего. (Оставлено как страховка; kill-волна идёт
     * через прямую подмену FlowNavigator-поля выше.)
     */
    @Autowired private com.zorrodev.bpm.engine.handler.HandlerRegistry handlerRegistry;
}
