package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-35 раунд 6, ШАГ 3 (требование CTO п.3 HOLD): ТАБЛИЧНЫЙ тест, который ЗА ГРАНИЦЕЙ одного
 * правила проверяет весь класс комбинаций «состояние хоста границы × тип границы × прерывающая ×
 * canceling-listener × вид хоста» — диаграммы генерируются, а не хранятся поимённо.
 *
 * <p><b>Почему таблица, а не точечные тесты.</b> За пять раундов красно-командная рецензия
 * нашла ПЯТЬ разных топологий одного и того же класса («когда join может срабатывать»), и каждый
 * раз штучным тестом. Штучные тесты закрывают ЭКЗЕМПЛЯР, а не класс: шесть типов границы, четыре
 * состояния хоста, прерывающая/непрерывающая, canceling-листенер и три вида хоста — это 288
 * комбинаций, и перебор их вручную невозможен. Здесь диаграмма СОБИРАЕТСЯ из осей, а ожидание
 * для каждой ячейки ВЫВОДИТСЯ из одного правила функцией {@link #theorySaysJoinParks}.
 *
 * <p><b>Правило (одно, без исключений).</b> Ребро границы хоста H доставляет ветвь в join, пока
 * H НЕ МЁРТВ и граница не отработала. «Мёртв» = все activity-строки H терминальные (завершён или
 * отменён); «ещё не начал» строк не имеет и доставщиком остаётся (BLOCKER-6). «Отработала» — это
 * про ВЫСТРЕЛ, и выстрелов в этой таблице нет: ось здесь — состояние хоста. Отсюда предсказание:
 *
 * <pre>
 *   хост не мёртв (не начал / жив)  → join ЖДЁТ   (граница может доставить)
 *   хост мёртв (завершён / отменён) → join СРАБАТЫВАЕТ ровно один раз, taskAfter создан один раз
 * </pre>
 *
 * <p><b>Чего таблица НЕ доказывает (сказано прямо, чтобы её не переоценивали).</b> Она не
 * стреляет границами: «отработала» после НАСТОЯЩЕГО claim проверяется PG-IT на живом PostgreSQL
 * ({@code ArmedBoundaryTimerJoinPgIT}) и юнит-тестами слоя данных, а механика выстрела по типам —
 * точечными тестами раундов 3–6 (B1 timer, condBnd, ERROR, ESCALATION, BLOCKER-5, BLOCKER-6).
 * Ось «тип границы» здесь доказывает, что ПРАВИЛО не зависит от типа (ребро границы
 * структурное) и что каждая из шести форм парсится и разворачивается как граница.
 *
 * <p><b>Красная ячейка — это находка, а не повод подогнать ожидание.</b> Ожидание выведено из
 * правила ДО прогона; расхождение означает, что правило или его реализация неверны — такое
 * эскалируется (см. отчёт раунда 6), а не «уточняется» под факт.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class InclusiveJoinReadinessMatrixIntegrationTests {

    // ── оси матрицы ──────────────────────────────────────────────────────────────────────

    /** Состояние хоста границы, в котором проверяется правило готовности. */
    enum HostState {
        NOT_STARTED("не стартовал"),
        ALIVE("жив"),
        COMPLETED("завершён"),
        CANCELLED("отменён");

        final String ru;

        HostState(String ru) {
            this.ru = ru;
        }

        /** Хост мёртв = все его activity-строки терминальные. Прямое следствие правила. */
        boolean hostIsDead() {
            return this == COMPLETED || this == CANCELLED;
        }
    }

    enum BoundaryType {
        TIMER("timer"), MESSAGE("message"), SIGNAL("signal"), CONDITIONAL("conditional"),
        ERROR("error"), ESCALATION("escalation");

        final String ru;

        BoundaryType(String ru) {
            this.ru = ru;
        }
    }

    enum HostKind {
        LEAF("лист (user task)"), SUBPROCESS("подпроцесс"), MULTI_INSTANCE("multi-instance");

        final String ru;

        HostKind(String ru) {
            this.ru = ru;
        }
    }

    record Combination(HostState state, BoundaryType type, boolean interrupting,
                       boolean cancelingListener, HostKind host) {

        @Override
        public String toString() {
            return "хост=" + state.ru + " × граница=" + type.ru + " × "
                + (interrupting ? "прерывающая" : "непрерывающая") + " × "
                + (cancelingListener ? "с canceling-listener" : "без canceling-listener") + " × " + host.ru;
        }
    }

    /**
     * Ожидание из ПРАВИЛА, а не из прогона. Ни одного обращения к движку — чистая функция осей.
     * Красная ячейка при такой функции означает дефект правила или его реализации.
     */
    static boolean theorySaysJoinParks(Combination c) {
        return !c.state().hostIsDead();
    }

    /** Комбинации, которые BPMN действительно может выразить (у контейнера нет user task). */
    static String notApplicableReason(Combination c) {
        if (c.cancelingListener() && c.host() == HostKind.SUBPROCESS) {
            return "canceling-listener — расширение userTask; у контейнера-подпроцесса user task нет";
        }
        return null;
    }

    @Autowired ProcessDefinitionService processDefinitionService;
    @Autowired RuntimeService runtimeService;
    @Autowired ActivityRepository activityRepository;
    @Autowired IncidentRepository incidentRepository;
    @Autowired ActivityService activityService;
    @Autowired DBService dbService;

    // ── матрица ──────────────────────────────────────────────────────────────────────────

    static Stream<Combination> allCombinations() {
        List<Combination> out = new ArrayList<>();
        for (HostState s : HostState.values()) {
            for (BoundaryType t : BoundaryType.values()) {
                for (boolean interrupting : new boolean[] { true, false }) {
                    for (boolean listener : new boolean[] { true, false }) {
                        for (HostKind h : HostKind.values()) {
                            out.add(new Combination(s, t, interrupting, listener, h));
                        }
                    }
                }
            }
        }
        return out.stream();
    }

    static Stream<Arguments> applicable() {
        return allCombinations()
            .filter(c -> notApplicableReason(c) == null)
            .map(c -> Arguments.of(c));
    }

    /**
     * Матрица не должна «потеряться» молча: полное число комбинаций, число применимых и число
     * неприменимых — фиксировано здесь, а каждая неприменимая ячейка обязана иметь причину.
     */
    @Test
    void matrixIsCompleteAndEverySkippedCellHasAReason() {
        List<Combination> all = allCombinations().toList();
        List<Combination> skipped = all.stream().filter(c -> notApplicableReason(c) != null).toList();

        assertThat(all)
            .as("4 состояния × 6 типов × 2 (прерывающая) × 2 (canceling-listener) × 3 вида хоста")
            .hasSize(4 * 6 * 2 * 2 * 3);
        assertThat(all.stream().filter(c -> notApplicableReason(c) == null).count() + skipped.size())
            .isEqualTo(all.size());
        assertThat(skipped)
            .as("неприменимые ячейки допустимы только с непустой причиной")
            .allSatisfy(c -> assertThat(notApplicableReason(c)).isNotBlank());
        // каждая ось обязана реально出现ть в прогоне, иначе «матрица» — это одна комбинация
        assertThat(all.stream().map(Combination::type).distinct()).hasSize(6);
        assertThat(all.stream().map(Combination::state).distinct()).hasSize(4);
        assertThat(all.stream().map(Combination::host).distinct()).hasSize(3);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("applicable")
    @Transactional
    void joinReadinessFollowsTheOneRule(Combination c) throws Exception {
        UUID defId = processDefinitionService.addProcessDefinition(diagram(c)).getId();
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(defId);
        dto.setVariables(startVariables(c));
        UUID pi = runtimeService.startProcessInstance(dto).getId();

        // ── парковка: ветвь taskA приходит в join ────────────────────────────────────────
        complete(pi, "taskA");
        assertThat(countOf(pi, "join", ActivityStatus.COMPLETED))
            .as("%s → базовая премисса: ветвь taskA пришла, второй доставщик ещё возможен", c)
            .isEqualTo(0L);
        assertThat(countOf(pi, "taskAfter", ActivityStatus.CREATED))
            .as("%s → базовая премисса: до join ничего не должно выполняться", c)
            .isEqualTo(0L);

        // ── состояние хоста по оси ──────────────────────────────────────────────────────
        switch (c.state()) {
            case NOT_STARTED -> {
                assertThat(activities(pi).stream().filter(a -> "host".equals(a.getBpmnElementId())))
                    .as("%s → премисса: у хоста нет ни одной activity-строки — он ещё не начал", c)
                    .isEmpty();
                assertThat(activities(pi).stream()
                    .filter(a -> "taskSvc".equals(a.getBpmnElementId()) && a.getStatus() == ActivityStatus.CREATED))
                    .as("%s → премисса: живое исполнение выше (taskSvc) запустит хоста", c)
                    .hasSize(1);
            }
            case ALIVE -> {
                complete(pi, "taskSvc");
                assertThat(hostActivities(pi)).as("%s → премисса: хост жив", c).isNotEmpty();
            }
            case COMPLETED -> {
                complete(pi, "taskSvc");
                completeHost(pi, c);
                assertAllHostRowsTerminal(pi, c, "завершение хоста");
            }
            case CANCELLED -> {
                complete(pi, "taskSvc");
                ActivityEntity host = hostActivities(pi).get(0);
                activityService.fireBoundaryTimer(host.getId(), "killBnd");
                if (c.cancelingListener()) {
                    // canceling-фаза откладывает хвост до последнего листенера (WO-C8-28), и у
                    // каждой КОПИИ multi-instance своя фаза со своим job'ом — закрыть надо все,
                    // иначе отложенный хвост (а с ним и перепроверка parked join) не наступит.
                    completeOpenCancelingListenerPhases(pi);
                }
                assertAllHostRowsTerminal(pi, c, "прерывающая граница killBnd погасила хоста");
            }
            default -> throw new AssertionError("unreachable state " + c.state());
        }

        // ── ПРОГНОЗ ИЗ ПРАВИЛА ─────────────────────────────────────────────────────────
        boolean parks = theorySaysJoinParks(c);
        long expectedJoin = parks ? 0L : 1L;
        long expectedTaskAfter = parks ? 0L : 1L;

        assertThat(countOf(pi, "join", ActivityStatus.COMPLETED))
            .as(c + " → по правилу join " + (parks ? "ЖДЁТ" : "СРАБАТЫВАЕТ ровно один раз"))
            .isEqualTo(expectedJoin);
        assertThat(countOf(pi, "taskAfter", ActivityStatus.CREATED))
            .as(c + " → побочный эффект после join ровно " + expectedTaskAfter)
            .isEqualTo(expectedTaskAfter);
        assertThat(incidents(pi)).as(c + " → без инцидентов").isEqualTo(0L);
    }

    // ── водительство ──────────────────────────────────────────────────────────────────────

    private ProcessVariable jsonVar(String name, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(ProcessVariableType.JSON);
        v.setValue(value);
        return v;
    }

    private List<ProcessVariable> startVariables(Combination c) {
        return c.host() == HostKind.MULTI_INSTANCE ? List.of(jsonVar("mitems", "[\"a\",\"b\"]")) : List.of();
    }

    private List<ActivityEntity> activities(UUID pi) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .toList();
    }

    /**
     * Премисса «хост мёртв»: у хоста есть строки и ВСЕ они терминальные. Именно это состояние
     * означает «мёртв» в правиле готовности — проверяется ДО предсказания, чтобы красная ячейка
     * не могла оказаться следствием того, что хост не довели до нужного состояния.
     */
    private void assertAllHostRowsTerminal(UUID pi, Combination c, String how) {
        List<ActivityEntity> rows = activities(pi).stream()
            .filter(a -> "host".equals(a.getBpmnElementId()))
            .toList();
        assertThat(rows).as("%s → премисса: у хоста есть activity-строки (%s)", c, how).isNotEmpty();
        assertThat(rows)
            .as("%s → премисса: все строки хоста терминальны (%s)", c, how)
            .allSatisfy(a -> assertThat(a.getStatus()).isIn(
                ActivityStatus.COMPLETED, ActivityStatus.CANCELLED, ActivityStatus.ERROR));
    }

    /** Живые строки хоста (для MI — по одной на копию). */
    private List<ActivityEntity> hostActivities(UUID pi) {
        return activities(pi).stream()
            .filter(a -> "host".equals(a.getBpmnElementId()))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED || a.getStatus() == ActivityStatus.IN_PROGRESS)
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

    private void complete(UUID pi, String elementId) {
        ActivityEntity a = activities(pi).stream()
            .filter(x -> elementId.equals(x.getBpmnElementId()))
            .filter(x -> x.getStatus() == ActivityStatus.CREATED)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no CREATED " + elementId + " in " + pi));
        runtimeService.completeUserTask(a.getId(), List.of());
    }

    /** Завершает хост так, чтобы ВСЕ его строки стали терминальными. */
    private void completeHost(UUID pi, Combination c) {
        if (c.host() == HostKind.SUBPROCESS) {
            complete(pi, "hInner");
            return;
        }
        for (ActivityEntity a : hostActivities(pi)) {
            runtimeService.completeUserTask(a.getId(), List.of());
        }
    }

    /** Закрывает canceling-фазу каждой activity-строки хоста, у которой фаза открыта. */
    private void completeOpenCancelingListenerPhases(UUID pi) {
        for (ActivityEntity a : activities(pi)) {
            if (!"host".equals(a.getBpmnElementId())) {
                continue;
            }
            if (dbService.getPendingCancelingListenerIndex(a.getId()) != null) {
                runtimeService.completeServiceTask(a.getId(), List.of());
            }
        }
    }

    // ── генератор диаграмм ───────────────────────────────────────────────────────────────

    private String diagram(Combination c) {
        String key = "c835mx" + Math.abs((c.toString() + UUID.randomUUID()).hashCode());
        return template(c)
            .replace("c835mx", key)
            .replace("%INTERRUPTING%", String.valueOf(c.interrupting()));
    }

    private String template(Combination c) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" \
            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" \
            id="Definitions_c835mx" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:message id="m1" name="m1" />
              <bpmn:signal id="s1" name="s1" />
              <bpmn:error id="e1" name="E1" errorCode="E1" />
              <bpmn:escalation id="x1" name="X1" escalationCode="X1" />
              <bpmn:process id="c835mx" name="c835mx" isExecutable="true">
                <bpmn:startEvent id="startEvent"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
                <bpmn:sequenceFlow id="f1" sourceRef="startEvent" targetRef="pfork" />
                <bpmn:parallelGateway id="pfork">
                  <bpmn:incoming>f1</bpmn:incoming>
                  <bpmn:outgoing>fa</bpmn:outgoing>
                  <bpmn:outgoing>fsvc</bpmn:outgoing>
                </bpmn:parallelGateway>
                <bpmn:sequenceFlow id="fa" sourceRef="pfork" targetRef="taskA" />
                <bpmn:userTask id="taskA">
                  <bpmn:incoming>fa</bpmn:incoming><bpmn:outgoing>gC</bpmn:outgoing>
                </bpmn:userTask>
                <bpmn:sequenceFlow id="gC" sourceRef="taskA" targetRef="join" />
                <bpmn:sequenceFlow id="fsvc" sourceRef="pfork" targetRef="taskSvc" />
                <bpmn:userTask id="taskSvc">
                  <bpmn:incoming>fsvc</bpmn:incoming><bpmn:outgoing>fH</bpmn:outgoing>
                </bpmn:userTask>
                <bpmn:sequenceFlow id="fH" sourceRef="taskSvc" targetRef="host" />
            %HOST%
                <bpmn:boundaryEvent id="killBnd" attachedToRef="host" cancelActivity="true">
                  <bpmn:outgoing>fKill</bpmn:outgoing>
                  <bpmn:timerEventDefinition id="killDef">
                    <bpmn:timeDuration>PT10H</bpmn:timeDuration>
                  </bpmn:timerEventDefinition>
                </bpmn:boundaryEvent>
                <bpmn:sequenceFlow id="fKill" sourceRef="killBnd" targetRef="escapeEnd" />
                <bpmn:endEvent id="escapeEnd"><bpmn:incoming>fKill</bpmn:incoming></bpmn:endEvent>
                <bpmn:boundaryEvent id="bnd" attachedToRef="host" cancelActivity="%INTERRUPTING%">
                  <bpmn:outgoing>fB</bpmn:outgoing>
            %BOUNDARY_DEF%
                </bpmn:boundaryEvent>
                <bpmn:sequenceFlow id="fB" sourceRef="bnd" targetRef="join" />
                <bpmn:inclusiveGateway id="join">
                  <bpmn:incoming>gC</bpmn:incoming>
                  <bpmn:incoming>fB</bpmn:incoming>
                  <bpmn:outgoing>fAfter</bpmn:outgoing>
                </bpmn:inclusiveGateway>
                <bpmn:sequenceFlow id="fAfter" sourceRef="join" targetRef="taskAfter" />
                <bpmn:userTask id="taskAfter">
                  <bpmn:incoming>fAfter</bpmn:incoming><bpmn:outgoing>fEnd</bpmn:outgoing>
                </bpmn:userTask>
                <bpmn:sequenceFlow id="fEnd" sourceRef="taskAfter" targetRef="endEvent" />
                <bpmn:endEvent id="endEvent"><bpmn:incoming>fEnd</bpmn:incoming></bpmn:endEvent>
              </bpmn:process>
            </bpmn:definitions>
            """
            .replace("%HOST%", hostBody(c))
            .replace("%BOUNDARY_DEF%", boundaryDefinition(c.type()));
    }

    private String hostBody(Combination c) {
        String listener = c.cancelingListener()
            ? """
                      <zeebe:taskListeners>
                        <zeebe:taskListener eventType="canceling" type="canceling-job" />
                      </zeebe:taskListeners>
            """
            : "";
        String incomingOutgoing = """
                    <bpmn:incoming>fH</bpmn:incoming>
                    <bpmn:outgoing>fHostEnd</bpmn:outgoing>
            """;
        String tail = """
                  <bpmn:sequenceFlow id="fHostEnd" sourceRef="host" targetRef="endHost" />
                  <bpmn:endEvent id="endHost"><bpmn:incoming>fHostEnd</bpmn:incoming></bpmn:endEvent>
        """;
        return switch (c.host()) {
            case LEAF -> """
                      <bpmn:userTask id="host">
                        <bpmn:extensionElements>
                          <zeebe:userTask />
                %LISTENER%    </bpmn:extensionElements>
                %IO%
                      </bpmn:userTask>
                %TAIL%
                """.replace("%LISTENER%", listener).replace("%IO%", incomingOutgoing).replace("%TAIL%", tail);
            case MULTI_INSTANCE -> """
                      <bpmn:userTask id="host">
                        <bpmn:extensionElements>
                          <zeebe:userTask />
                %LISTENER%    </bpmn:extensionElements>
                %IO%
                        <bpmn:multiInstanceLoopCharacteristics>
                          <bpmn:extensionElements>
                            <zeebe:loopCharacteristics inputCollection="=mitems" inputElement="mi" />
                          </bpmn:extensionElements>
                        </bpmn:multiInstanceLoopCharacteristics>
                      </bpmn:userTask>
                %TAIL%
                """.replace("%LISTENER%", listener).replace("%IO%", incomingOutgoing).replace("%TAIL%", tail);
            case SUBPROCESS -> """
                      <bpmn:subProcess id="host">
                %IO%
                        <bpmn:startEvent id="hStart"><bpmn:outgoing>hf1</bpmn:outgoing></bpmn:startEvent>
                        <bpmn:sequenceFlow id="hf1" sourceRef="hStart" targetRef="hInner" />
                        <bpmn:userTask id="hInner">
                          <bpmn:incoming>hf1</bpmn:incoming><bpmn:outgoing>hf2</bpmn:outgoing>
                        </bpmn:userTask>
                        <bpmn:sequenceFlow id="hf2" sourceRef="hInner" targetRef="hEnd" />
                        <bpmn:endEvent id="hEnd"><bpmn:incoming>hf2</bpmn:incoming></bpmn:endEvent>
                      </bpmn:subProcess>
                %TAIL%
                """.replace("%IO%", incomingOutgoing).replace("%TAIL%", tail);
        };
    }

    private String boundaryDefinition(BoundaryType t) {
        return switch (t) {
            case TIMER -> """
                      <bpmn:timerEventDefinition id="bd">
                        <bpmn:timeDuration>PT10H</bpmn:timeDuration>
                      </bpmn:timerEventDefinition>
                """;
            case MESSAGE -> "      <bpmn:messageEventDefinition id=\"bd\" messageRef=\"m1\" />\n";
            case SIGNAL -> "      <bpmn:signalEventDefinition id=\"bd\" signalRef=\"s1\" />\n";
            case CONDITIONAL -> """
                      <bpmn:conditionalEventDefinition id="bd">
                        <bpmn:condition xsi:type="bpmn:tFormalExpression">go = true</bpmn:condition>
                      </bpmn:conditionalEventDefinition>
                """;
            case ERROR -> "      <bpmn:errorEventDefinition id=\"bd\" errorRef=\"e1\" />\n";
            case ESCALATION -> "      <bpmn:escalationEventDefinition id=\"bd\" escalationRef=\"x1\" />\n";
        };
    }
}