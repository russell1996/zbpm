package com.zorrodev.bpm.engine.scenario.event;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WO-OPS-28 ФАЗА 4: генератор сценариев среза «события» (start/end/intermediate/boundary).
 *
 * <p>Срез F4 покрывает то, что ФАЗА 1 (задачи и данные) и ФАЗА 2 (потоки) не покрывают:
 * <ul>
 *   <li><b>start</b>: none, signal, timer (duration/cycle/date);</li>
 *   <li><b>end</b>: none, error (без обработчика и с границей), escalation (с границей),
 *       terminate, cancel (в транзакции);</li>
 *   <li><b>intermediate</b>: catch (signal/timer/link), throw (signal/escalation);</li>
 *   <li><b>boundary</b>: timer (прерывающая/непрерывающая) на задаче и на контейнере,
 *       signal (прерывающая/непрерывающая) на задаче, error на задаче;</li>
 *   <li><b>граница на MI-контейнере</b> — расхождение ENG-3 («на теле MI vs per-instance»);</li>
 *   <li><b>таймеры</b>: duration/cycle/date в трёх местах (start/catch/boundary);</li>
 *   <li><b>инциденты</b>: error-end без обработчика и разбор инцидента;</li>
 *   <li><b>отмена инстанса</b>.</li>
 * </ul>
 *
 * <p><b>Чего здесь нет намеренно.</b> События-сообщения (message start/catch/throw, граница
 * по сообщению) уже измерены в WO-OPS-30 Ф2в — матрица
 * {@code docs/analysis/message-conformance-matrix.md}; повторять их здесь нельзя (в отчёте
 * стоит ссылка, а не второй замер). То же — условные события (conditional): Zeebe 8.5 их
 * не принимает вовсе, поэтому по ним сценарий «живого оракула» не существует в принципе
 * (в отчёте — отдельная строка «нет оракула», а не фиктивное расхождение).
 *
 * <p>Модели собираются в Zeebe-совместимом диалекте: те же id, тот же {@code zeebe:taskDefinition},
 * те же определения сигнала/ошибки/эскалации. Иначе «дифференциальный прогон» сравнивал бы
 * разные модели, а не поведение.
 *
 * <p>Сценарии детерминированы: без случайности, без внешних зависимостей — одна и та же
 * матрица получается при каждом запуске, поэтому её можно сравнивать с замером оракула.
 */
public final class EventScenarioGenerator {

    /** Тип job'а наблюдательной задачи (её payload = наблюдаемое состояние). */
    public static final String DUMP_JOB_TYPE = "dump";

    private EventScenarioGenerator() {
    }

    /** Полная матрица среза F4. */
    public static List<EventScenario> generate() {
        List<EventScenario> out = new ArrayList<>();
        out.add(startNone());
        out.add(startSignal());
        out.add(startTimerDuration());
        out.add(startTimerCycle());
        out.add(startTimerDate());
        out.add(endNone());
        out.add(endErrorUnhandled());
        out.add(endErrorBoundary());
        out.add(endEscalationBoundary());
        out.add(endTerminate());
        out.add(endCancelInTransaction());
        out.add(catchSignal());
        out.add(catchTimerDuration());
        out.add(catchTimerDate());
        out.add(catchLink());
        out.add(throwSignal());
        out.add(throwEscalationBoundary());
        out.add(boundaryTimerInterruptingTask());
        out.add(boundaryTimerNonInterruptingTask());
        out.add(boundarySignalInterruptingTask());
        out.add(boundarySignalNonInterruptingTask());
        out.add(boundaryErrorInterruptingTask());
        out.add(boundaryTimerInterruptingContainer());
        out.add(boundaryTimerNonInterruptingContainer());
        out.add(boundaryTimerOnMiContainer());
        out.add(boundaryCycleTimer());
        out.add(boundaryDateTimer());
        out.add(incidentResolve());
        out.add(cancelInstance());
        out.add(endCompensate());
        out.add(boundaryConditional());
        out.add(boundaryWithoutEventDefinition());
        out.add(boundaryDanglingHost());
        out.add(boundaryDanglingErrorRef());
        out.add(camundaExportStartTag());
        return out;
    }

    // ------------------------------------------------------------------
    // START
    // ------------------------------------------------------------------

    private static EventScenario startNone() {
        String bpmn = defs("f4StartNone", "",
                start("start", "f1")
                        + flow("f1", "start", "dump")
                        + svc("dump", DUMP_JOB_TYPE, "f1", "f2")
                        + flow("f2", "dump", "end")
                        + end("end", "f2"));
        return scenario("f4-start-none", "start", "startEvent(none)", bpmn,
                EventScenario.StartMode.PLAIN, null,
                List.of(EventScenarioAction.observe(), EventScenarioAction.complete("dump")),
                List.of(DUMP_JOB_TYPE), 5000,
                "Базовый срез: обычный старт, наблюдательная задача в корне, конец процесса.");
    }

    private static EventScenario startSignal() {
        String bpmn = defs("f4StartSignal", signal("SigStart", "f4-sig-start"),
                start("start", "f1",
                        "<bpmn:signalEventDefinition id=\"sed\" signalRef=\"SigStart\" />")
                        + flow("f1", "start", "dump")
                        + svc("dump", DUMP_JOB_TYPE, "f1", "f2")
                        + flow("f2", "dump", "end")
                        + end("end", "f2"));
        return scenario("f4-start-signal", "start", "startEvent(signal)", bpmn,
                EventScenario.StartMode.SIGNAL, "f4-sig-start",
                List.of(EventScenarioAction.signal("f4-sig-start"),
                        EventScenarioAction.waitMs(800),
                        EventScenarioAction.observe(),
                        EventScenarioAction.complete("dump")),
                List.of(DUMP_JOB_TYPE), 5000,
                "Старт по сигналу: инстанс создаёт бросок сигнала (а не вызов старта).");
    }

    private static EventScenario startTimerDuration() {
        String bpmn = defs("f4StartTimerDuration", "",
                start("start", "f1",
                        "<bpmn:timerEventDefinition id=\"ted\"><bpmn:timeDuration xsi:type=\"bpmn:tFormalExpression\">PT1S</bpmn:timeDuration></bpmn:timerEventDefinition>")
                        + flow("f1", "start", "dump")
                        + svc("dump", DUMP_JOB_TYPE, "f1", "f2")
                        + flow("f2", "dump", "end")
                        + end("end", "f2"));
        return scenario("f4-start-timer-duration", "start", "startEvent(timer/duration)", bpmn,
                EventScenario.StartMode.TIMER, null,
                List.of(EventScenarioAction.waitMs(1500),
                        EventScenarioAction.fireTimers(),
                        EventScenarioAction.waitMs(800),
                        EventScenarioAction.observe(),
                        EventScenarioAction.complete("dump")),
                List.of(DUMP_JOB_TYPE), 8000,
                "Старт по таймеру длительности: инстанс создаёт сработавший таймер.");
    }

    private static EventScenario startTimerCycle() {
        String bpmn = defs("f4StartTimerCycle", "",
                start("start", "f1",
                        "<bpmn:timerEventDefinition id=\"ted\"><bpmn:timeCycle xsi:type=\"bpmn:tFormalExpression\">R3/PT1S</bpmn:timeCycle></bpmn:timerEventDefinition>")
                        + flow("f1", "start", "dump")
                        + svc("dump", DUMP_JOB_TYPE, "f1", "f2")
                        + flow("f2", "dump", "end")
                        + end("end", "f2"));
        return scenario("f4-start-timer-cycle", "start", "startEvent(timer/cycle)", bpmn,
                EventScenario.StartMode.TIMER, null,
                List.of(EventScenarioAction.waitMs(1500),
                        EventScenarioAction.fireTimers(),
                        EventScenarioAction.waitMs(800),
                        EventScenarioAction.observe(),
                        EventScenarioAction.complete("dump")),
                List.of(DUMP_JOB_TYPE), 8000,
                "Старт по ЦИКЛИЧЕСКОМУ таймеру: R3/PT1S — инстанс создаётся на каждом цикле "
                        + "(цикл ОГРАНИЧЕН тремя повторами: неограниченный R/… на общем стенде "
                        + "бесконечно плодит инстансы).");
    }

    private static EventScenario startTimerDate() {
        String bpmn = defs("f4StartTimerDate", "",
                start("start", "f1",
                        "<bpmn:timerEventDefinition id=\"ted\"><bpmn:timeDate xsi:type=\"bpmn:tFormalExpression\">2020-01-01T00:00:00Z</bpmn:timeDate></bpmn:timerEventDefinition>")
                        + flow("f1", "start", "dump")
                        + svc("dump", DUMP_JOB_TYPE, "f1", "f2")
                        + flow("f2", "dump", "end")
                        + end("end", "f2"));
        return scenario("f4-start-timer-date", "start", "startEvent(timer/date, прошедшая)", bpmn,
                EventScenario.StartMode.TIMER, null,
                List.of(EventScenarioAction.fireTimers(),
                        EventScenarioAction.waitMs(800),
                        EventScenarioAction.observe(),
                        EventScenarioAction.complete("dump")),
                List.of(DUMP_JOB_TYPE), 5000,
                "Старт по таймеру-дате в прошлом: должен сработать немедленно.");
    }

    // ------------------------------------------------------------------
    // END
    // ------------------------------------------------------------------

    private static EventScenario endNone() {
        String bpmn = defs("f4EndNone", "",
                start("start", "f1")
                        + flow("f1", "start", "dump")
                        + svc("dump", DUMP_JOB_TYPE, "f1", "f2")
                        + flow("f2", "dump", "end")
                        + end("end", "f2"));
        return scenario("f4-end-none", "end", "endEvent(none)", bpmn,
                EventScenario.StartMode.PLAIN, null,
                List.of(EventScenarioAction.observe(), EventScenarioAction.complete("dump")),
                List.of(DUMP_JOB_TYPE), 5000,
                "Обычный конец процесса: после наблюдательной задачи инстанс завершается.");
    }

    private static EventScenario endErrorUnhandled() {
        String bpmn = defs("f4EndErrorUnhandled", error("ErrUnh", "f4-err-unhandled", "E-UNH"),
                start("start", "f1")
                        + flow("f1", "start", "work")
                        + svc("work", "work", "f1", "f2")
                        + flow("f2", "work", "errEnd")
                        + "<bpmn:endEvent id=\"errEnd\"><bpmn:incoming>f2</bpmn:incoming>"
                        + "<bpmn:errorEventDefinition id=\"eed\" errorRef=\"ErrUnh\" /></bpmn:endEvent>");
        return scenario("f4-end-error-unhandled", "end", "endEvent(error) без обработчика", bpmn,
                EventScenario.StartMode.PLAIN, null,
                List.of(EventScenarioAction.complete("work"), EventScenarioAction.waitMs(800),
                        EventScenarioAction.observe()),
                List.of("work"), 5000,
                "Error end event без обработчика: ожидается инцидент, инстанс НЕ завершается.");
    }

    private static EventScenario endErrorBoundary() {
        String bpmn = defs("f4EndErrorBoundary", error("ErrBnd", "f4-err-bnd", "E-BND"),
                start("start", "f1")
                        + flow("f1", "start", "sub")
                        + subProcessError("sub", "f1", "f2", "ErrBnd")
                        + flow("f2", "sub", "dump")
                        + svc("dump", DUMP_JOB_TYPE, "f2", "f3")
                        + flow("f3", "dump", "end")
                        + end("end", "f3")
                        + "<bpmn:boundaryEvent id=\"bnd\" name=\"bnd\" attachedToRef=\"sub\" cancelActivity=\"true\">"
                        + "<bpmn:outgoing>fB</bpmn:outgoing>"
                        + "<bpmn:errorEventDefinition id=\"bed\" errorRef=\"ErrBnd\" /></bpmn:boundaryEvent>"
                        + flow("fB", "bnd", "afterB")
                        + svc("afterB", "afterB", "fB", "fB2")
                        + flow("fB2", "afterB", "dump2")
                        + svc("dump2", "dump2", "fB2", "fB3")
                        + flow("fB3", "dump2", "endB")
                        + end("endB", "fB3"));
        return scenario("f4-end-error-boundary", "end", "endEvent(error) + граница ошибки", bpmn,
                EventScenario.StartMode.PLAIN, null,
                List.of(EventScenarioAction.waitMs(600),
                        EventScenarioAction.complete("afterB"),
                        EventScenarioAction.observe(),
                        EventScenarioAction.complete("dump2")),
                List.of(DUMP_JOB_TYPE, "afterB", "dump2"), 8000,
                "Error end event В подпроцессе, ловится границей ошибки на подпроцессе.",
                "dump2");
    }

    private static EventScenario endEscalationBoundary() {
        String bpmn = defs("f4EndEscalationBoundary", escalation("EscBnd", "f4-esc-bnd", "ESC-BND"),
                start("start", "f1")
                        + flow("f1", "start", "sub")
                        + subProcessEscalation("sub", "f1", "f2", "EscBnd")
                        + flow("f2", "sub", "dump")
                        + svc("dump", DUMP_JOB_TYPE, "f2", "f3")
                        + flow("f3", "dump", "end")
                        + end("end", "f3")
                        + "<bpmn:boundaryEvent id=\"bnd\" name=\"bnd\" attachedToRef=\"sub\" cancelActivity=\"true\">"
                        + "<bpmn:outgoing>fB</bpmn:outgoing>"
                        + "<bpmn:escalationEventDefinition id=\"bed\" escalationRef=\"EscBnd\" /></bpmn:boundaryEvent>"
                        + flow("fB", "bnd", "afterB")
                        + svc("afterB", "afterB", "fB", "fB2")
                        + flow("fB2", "afterB", "dump2")
                        + svc("dump2", "dump2", "fB2", "fB3")
                        + flow("fB3", "dump2", "endB")
                        + end("endB", "fB3"));
        return scenario("f4-end-escalation-boundary", "end", "endEvent(escalation) + граница эскалации", bpmn,
                EventScenario.StartMode.PLAIN, null,
                List.of(EventScenarioAction.waitMs(600),
                        EventScenarioAction.complete("afterB"),
                        EventScenarioAction.observe(),
                        EventScenarioAction.complete("dump2")),
                List.of(DUMP_JOB_TYPE, "afterB", "dump2"), 8000,
                "Escalation end event В подпроцессе, ловится границей эскалации.",
                "dump2");
    }

    private static EventScenario endTerminate() {
        String bpmn = defs("f4EndTerminate", "",
                start("start", "f1")
                        + flow("f1", "start", "fork")
                        + "<bpmn:parallelGateway id=\"fork\"><bpmn:incoming>f1</bpmn:incoming>"
                        + "<bpmn:outgoing>f2</bpmn:outgoing><bpmn:outgoing>f3</bpmn:outgoing></bpmn:parallelGateway>"
                        + flow("f2", "fork", "workA")
                        + svc("workA", "workA", "f2", "f2b")
                        + flow("f2b", "workA", "termEnd")
                        + "<bpmn:endEvent id=\"termEnd\"><bpmn:incoming>f2b</bpmn:incoming>"
                        + "<bpmn:terminateEventDefinition id=\"ted\" /></bpmn:endEvent>"
                        + flow("f3", "fork", "workB")
                        + svc("workB", "workB", "f3", "f4")
                        + flow("f4", "workB", "endB")
                        + end("endB", "f4"));
        return scenario("f4-end-terminate", "end", "endEvent(terminate)", bpmn,
                EventScenario.StartMode.PLAIN, null,
                List.of(EventScenarioAction.complete("workA"), EventScenarioAction.waitMs(800),
                        EventScenarioAction.observe()),
                List.of("workA", "workB"), 5000,
                "Terminate end event гасит ВСЕ ветви инстанса (вторая ветвь должна умереть).");
    }

    private static EventScenario endCancelInTransaction() {
        String bpmn = defs("f4EndCancelTx", "",
                start("start", "f1")
                        + flow("f1", "start", "tx")
                        + "<bpmn:transaction id=\"tx\" name=\"tx\"><bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>"
                        + "<bpmn:startEvent id=\"txStart\"><bpmn:outgoing>fs1</bpmn:outgoing></bpmn:startEvent>"
                        + flow("fs1", "txStart", "cancelEnd")
                        + "<bpmn:endEvent id=\"cancelEnd\"><bpmn:incoming>fs1</bpmn:incoming>"
                        + "<bpmn:cancelEventDefinition id=\"ced\" /></bpmn:endEvent>"
                        + "</bpmn:transaction>"
                        + flow("f2", "tx", "dump")
                        + svc("dump", DUMP_JOB_TYPE, "f2", "f3")
                        + flow("f3", "dump", "end")
                        + end("end", "f3"));
        return scenario("f4-end-cancel-in-transaction", "end", "endEvent(cancel) в транзакции", bpmn,
                EventScenario.StartMode.PLAIN, null,
                List.of(EventScenarioAction.waitMs(600),
                        EventScenarioAction.observe(),
                        EventScenarioAction.complete("dump")),
                List.of(DUMP_JOB_TYPE), 8000,
                "Cancel end event отменяет.scope транзакции, поток идёт дальше по исходящему потоку транзакции.");
    }

    // ------------------------------------------------------------------
    // INTERMEDIATE
    // ------------------------------------------------------------------

    private static EventScenario catchSignal() {
        String bpmn = defs("f4CatchSignal", signal("SigCatch", "f4-sig-catch"),
                start("start", "f1")
                        + flow("f1", "start", "sigCatch")
                        + "<bpmn:intermediateCatchEvent id=\"sigCatch\" name=\"sigCatch\">"
                        + "<bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>"
                        + "<bpmn:signalEventDefinition id=\"sed\" signalRef=\"SigCatch\" /></bpmn:intermediateCatchEvent>"
                        + flow("f2", "sigCatch", "dump")
                        + svc("dump", DUMP_JOB_TYPE, "f2", "f3")
                        + flow("f3", "dump", "end")
                        + end("end", "f3"));
        return scenario("f4-catch-signal", "intermediate", "intermediateCatchEvent(signal)", bpmn,
                EventScenario.StartMode.PLAIN, null,
                List.of(EventScenarioAction.signal("f4-sig-catch"),
                        EventScenarioAction.waitMs(600),
                        EventScenarioAction.observe(),
                        EventScenarioAction.complete("dump")),
                List.of(DUMP_JOB_TYPE), 5000,
                "Intermediate catch по сигналу: сигнал будит ожидающий catch.");
    }

    private static EventScenario catchTimerDuration() {
        String bpmn = defs("f4CatchTimerDuration", "",
                start("start", "f1")
                        + flow("f1", "start", "timerCatch")
                        + "<bpmn:intermediateCatchEvent id=\"timerCatch\" name=\"timerCatch\">"
                        + "<bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>"
                        + "<bpmn:timerEventDefinition id=\"ted\"><bpmn:timeDuration xsi:type=\"bpmn:tFormalExpression\">PT1S</bpmn:timeDuration></bpmn:timerEventDefinition>"
                        + "</bpmn:intermediateCatchEvent>"
                        + flow("f2", "timerCatch", "dump")
                        + svc("dump", DUMP_JOB_TYPE, "f2", "f3")
                        + flow("f3", "dump", "end")
                        + end("end", "f3"));
        return scenario("f4-catch-timer-duration", "intermediate", "intermediateCatchEvent(timer/duration)", bpmn,
                EventScenario.StartMode.PLAIN, null,
                List.of(EventScenarioAction.waitMs(1500),
                        EventScenarioAction.fireTimers(),
                        EventScenarioAction.waitMs(600),
                        EventScenarioAction.observe(),
                        EventScenarioAction.complete("dump")),
                List.of(DUMP_JOB_TYPE), 8000,
                "Intermediate catch по таймеру длительности.");
    }

    private static EventScenario catchTimerDate() {
        String bpmn = defs("f4CatchTimerDate", "",
                start("start", "f1")
                        + flow("f1", "start", "timerCatch")
                        + "<bpmn:intermediateCatchEvent id=\"timerCatch\" name=\"timerCatch\">"
                        + "<bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>"
                        + "<bpmn:timerEventDefinition id=\"ted\"><bpmn:timeDate xsi:type=\"bpmn:tFormalExpression\">2020-01-01T00:00:00Z</bpmn:timeDate></bpmn:timerEventDefinition>"
                        + "</bpmn:intermediateCatchEvent>"
                        + flow("f2", "timerCatch", "dump")
                        + svc("dump", DUMP_JOB_TYPE, "f2", "f3")
                        + flow("f3", "dump", "end")
                        + end("end", "f3"));
        return scenario("f4-catch-timer-date", "intermediate", "intermediateCatchEvent(timer/date, прошедшая)", bpmn,
                EventScenario.StartMode.PLAIN, null,
                List.of(EventScenarioAction.fireTimers(),
                        EventScenarioAction.waitMs(600),
                        EventScenarioAction.observe(),
                        EventScenarioAction.complete("dump")),
                List.of(DUMP_JOB_TYPE), 5000,
                "Intermediate catch по таймеру-дате в прошлом: срабатывает немедленно.");
    }

    private static EventScenario catchLink() {
        String bpmn = defs("f4CatchLink", "",
                start("start", "f1")
                        + flow("f1", "start", "linkThrow")
                        + "<bpmn:intermediateThrowEvent id=\"linkThrow\" name=\"linkThrow\">"
                        + "<bpmn:incoming>f1</bpmn:incoming>"
                        + "<bpmn:linkEventDefinition id=\"led1\" name=\"L1\" /></bpmn:intermediateThrowEvent>"
                        + "<bpmn:intermediateCatchEvent id=\"linkCatch\" name=\"linkCatch\">"
                        + "<bpmn:outgoing>f2</bpmn:outgoing>"
                        + "<bpmn:linkEventDefinition id=\"led2\" name=\"L1\" /></bpmn:intermediateCatchEvent>"
                        + flow("f2", "linkCatch", "dump")
                        + svc("dump", DUMP_JOB_TYPE, "f2", "f3")
                        + flow("f3", "dump", "end")
                        + end("end", "f3"));
        return scenario("f4-catch-link", "intermediate", "intermediateThrowEvent(link) + catch(link)", bpmn,
                EventScenario.StartMode.PLAIN, null,
                List.of(EventScenarioAction.waitMs(600),
                        EventScenarioAction.observe(),
                        EventScenarioAction.complete("dump")),
                List.of(DUMP_JOB_TYPE), 5000,
                "Link-события: бросок перепрыгивает к одноимённому catch (поток мимо throw не берётся).");
    }

    private static EventScenario throwSignal() {
        String bpmn = defs("f4ThrowSignal", signal("SigThrow", "f4-sig-throw"),
                start("start", "f1")
                        + flow("f1", "start", "sigThrow")
                        + "<bpmn:intermediateThrowEvent id=\"sigThrow\" name=\"sigThrow\">"
                        + "<bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>"
                        + "<bpmn:signalEventDefinition id=\"sed\" signalRef=\"SigThrow\" /></bpmn:intermediateThrowEvent>"
                        + flow("f2", "sigThrow", "dump")
                        + svc("dump", DUMP_JOB_TYPE, "f2", "f3")
                        + flow("f3", "dump", "end")
                        + end("end", "f3"));
        return scenario("f4-throw-signal", "intermediate", "intermediateThrowEvent(signal)", bpmn,
                EventScenario.StartMode.PLAIN, null,
                List.of(EventScenarioAction.waitMs(600),
                        EventScenarioAction.observe(),
                        EventScenarioAction.complete("dump")),
                List.of(DUMP_JOB_TYPE), 5000,
                "Intermediate throw по сигналу: сигнал рассылается, поток идёт дальше.");
    }

    private static EventScenario throwEscalationBoundary() {
        String bpmn = defs("f4ThrowEscalation", escalation("EscThrow", "f4-esc-throw", "ESC-THROW"),
                start("start", "f1")
                        + flow("f1", "start", "sub")
                        + "<bpmn:subProcess id=\"sub\" name=\"sub\"><bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>"
                        + "<bpmn:startEvent id=\"subStart\"><bpmn:outgoing>fs1</bpmn:outgoing></bpmn:startEvent>"
                        + flow("fs1", "subStart", "escThrow")
                        + "<bpmn:intermediateThrowEvent id=\"escThrow\" name=\"escThrow\">"
                        + "<bpmn:incoming>fs1</bpmn:incoming><bpmn:outgoing>fs2</bpmn:outgoing>"
                        + "<bpmn:escalationEventDefinition id=\"eed\" escalationRef=\"EscThrow\" /></bpmn:intermediateThrowEvent>"
                        + flow("fs2", "escThrow", "subEnd")
                        + end("subEnd", "fs2")
                        + "</bpmn:subProcess>"
                        + flow("f2", "sub", "dump")
                        + svc("dump", DUMP_JOB_TYPE, "f2", "f3")
                        + flow("f3", "dump", "end")
                        + end("end", "f3")
                        + "<bpmn:boundaryEvent id=\"bnd\" name=\"bnd\" attachedToRef=\"sub\" cancelActivity=\"true\">"
                        + "<bpmn:outgoing>fB</bpmn:outgoing>"
                        + "<bpmn:escalationEventDefinition id=\"bed\" escalationRef=\"EscThrow\" /></bpmn:boundaryEvent>"
                        + flow("fB", "bnd", "afterB")
                        + svc("afterB", "afterB", "fB", "fB2")
                        + flow("fB2", "afterB", "dump2")
                        + svc("dump2", "dump2", "fB2", "fB3")
                        + flow("fB3", "dump2", "endB")
                        + end("endB", "fB3"));
        return scenario("f4-throw-escalation-boundary", "intermediate",
                "intermediateThrowEvent(escalation) + граница эскалации", bpmn,
                EventScenario.StartMode.PLAIN, null,
                List.of(EventScenarioAction.waitMs(600),
                        EventScenarioAction.complete("afterB"),
                        EventScenarioAction.observe(),
                        EventScenarioAction.complete("dump2")),
                List.of(DUMP_JOB_TYPE, "afterB", "dump2"), 8000,
                "Бросок эскалации внутри подпроцесса ловится границей эскалации на подпроцессе.",
                "dump2");
    }

    // ------------------------------------------------------------------
    // BOUNDARY
    // ------------------------------------------------------------------

    private static EventScenario boundaryTimerInterruptingTask() {
        String bpmn = defs("f4BndTimerInt", "",
                start("start", "f1")
                        + flow("f1", "start", "host")
                        + svc("host", "host", "f1", "f2")
                        + flow("f2", "host", "endMain")
                        + end("endMain", "f2")
                        + "<bpmn:boundaryEvent id=\"bnd\" name=\"bnd\" attachedToRef=\"host\" cancelActivity=\"true\">"
                        + "<bpmn:outgoing>fB</bpmn:outgoing>"
                        + "<bpmn:timerEventDefinition id=\"ted\"><bpmn:timeDuration xsi:type=\"bpmn:tFormalExpression\">PT1S</bpmn:timeDuration></bpmn:timerEventDefinition>"
                        + "</bpmn:boundaryEvent>"
                        + flow("fB", "bnd", "afterB")
                        + svc("afterB", "afterB", "fB", "fB2")
                        + flow("fB2", "afterB", "dump")
                        + svc("dump", DUMP_JOB_TYPE, "fB2", "f3")
                        + flow("f3", "dump", "end")
                        + end("end", "f3"));
        return scenario("f4-bnd-timer-interrupting-task", "boundary",
                "boundaryEvent(timer, прерывающая) на задаче", bpmn,
                EventScenario.StartMode.PLAIN, null,
                List.of(EventScenarioAction.waitMs(1500),
                        EventScenarioAction.fireTimers(),
                        EventScenarioAction.waitMs(600),
                        EventScenarioAction.complete("afterB"),
                        EventScenarioAction.observe(),
                        EventScenarioAction.complete("dump")),
                List.of("host", "afterB", DUMP_JOB_TYPE), 8000,
                "Прерывающая граница по таймеру на сервисной задаче: хост отменяется, поток идёт по границе.");
    }

    private static EventScenario boundaryTimerNonInterruptingTask() {
        String bpmn = defs("f4BndTimerNonInt", "",
                start("start", "f1")
                        + flow("f1", "start", "host")
                        + svc("host", "host", "f1", "f2")
                        + flow("f2", "host", "endMain")
                        + end("endMain", "f2")
                        + "<bpmn:boundaryEvent id=\"bnd\" name=\"bnd\" attachedToRef=\"host\" cancelActivity=\"false\">"
                        + "<bpmn:outgoing>fB</bpmn:outgoing>"
                        + "<bpmn:timerEventDefinition id=\"ted\"><bpmn:timeDuration xsi:type=\"bpmn:tFormalExpression\">PT1S</bpmn:timeDuration></bpmn:timerEventDefinition>"
                        + "</bpmn:boundaryEvent>"
                        + flow("fB", "bnd", "afterB")
                        + svc("afterB", "afterB", "fB", "fB2")
                        + flow("fB2", "afterB", "dump")
                        + svc("dump", DUMP_JOB_TYPE, "fB2", "f3")
                        + flow("f3", "dump", "end")
                        + end("end", "f3"));
        return scenario("f4-bnd-timer-noninterrupting-task", "boundary",
                "boundaryEvent(timer, непрерывающая) на задаче", bpmn,
                EventScenario.StartMode.PLAIN, null,
                List.of(EventScenarioAction.waitMs(1500),
                        EventScenarioAction.fireTimers(),
                        EventScenarioAction.waitMs(600),
                        EventScenarioAction.complete("afterB"),
                        EventScenarioAction.observe(),
                        EventScenarioAction.complete("dump"),
                        EventScenarioAction.complete("host")),
                List.of("host", "afterB", DUMP_JOB_TYPE), 8000,
                "Непрерывающая граница по таймеру: хост ПРОДОЛЖАЕТ жить, граница даёт вторую ветвь.");
    }

    private static EventScenario boundarySignalInterruptingTask() {
        String bpmn = defs("f4BndSigInt", signal("SigBnd", "f4-sig-bnd"),
                start("start", "f1")
                        + flow("f1", "start", "host")
                        + svc("host", "host", "f1", "f2")
                        + flow("f2", "host", "endMain")
                        + end("endMain", "f2")
                        + "<bpmn:boundaryEvent id=\"bnd\" name=\"bnd\" attachedToRef=\"host\" cancelActivity=\"true\">"
                        + "<bpmn:outgoing>fB</bpmn:outgoing>"
                        + "<bpmn:signalEventDefinition id=\"sed\" signalRef=\"SigBnd\" /></bpmn:boundaryEvent>"
                        + flow("fB", "bnd", "afterB")
                        + svc("afterB", "afterB", "fB", "fB2")
                        + flow("fB2", "afterB", "dump")
                        + svc("dump", DUMP_JOB_TYPE, "fB2", "f3")
                        + flow("f3", "dump", "end")
                        + end("end", "f3"));
        return scenario("f4-bnd-signal-interrupting-task", "boundary",
                "boundaryEvent(signal, прерывающая) на задаче", bpmn,
                EventScenario.StartMode.PLAIN, null,
                List.of(EventScenarioAction.signal("f4-sig-bnd"),
                        EventScenarioAction.waitMs(600),
                        EventScenarioAction.complete("afterB"),
                        EventScenarioAction.observe(),
                        EventScenarioAction.complete("dump")),
                List.of("host", "afterB", DUMP_JOB_TYPE), 5000,
                "Прерывающая граница по сигналу на сервисной задаче.");
    }

    private static EventScenario boundarySignalNonInterruptingTask() {
        String bpmn = defs("f4BndSigNonInt", signal("SigBnd", "f4-sig-bnd"),
                start("start", "f1")
                        + flow("f1", "start", "host")
                        + svc("host", "host", "f1", "f2")
                        + flow("f2", "host", "endMain")
                        + end("endMain", "f2")
                        + "<bpmn:boundaryEvent id=\"bnd\" name=\"bnd\" attachedToRef=\"host\" cancelActivity=\"false\">"
                        + "<bpmn:outgoing>fB</bpmn:outgoing>"
                        + "<bpmn:signalEventDefinition id=\"sed\" signalRef=\"SigBnd\" /></bpmn:boundaryEvent>"
                        + flow("fB", "bnd", "afterB")
                        + svc("afterB", "afterB", "fB", "fB2")
                        + flow("fB2", "afterB", "dump")
                        + svc("dump", DUMP_JOB_TYPE, "fB2", "f3")
                        + flow("f3", "dump", "end")
                        + end("end", "f3"));
        return scenario("f4-bnd-signal-noninterrupting-task", "boundary",
                "boundaryEvent(signal, непрерывающая) на задаче", bpmn,
                EventScenario.StartMode.PLAIN, null,
                List.of(EventScenarioAction.signal("f4-sig-bnd"),
                        EventScenarioAction.waitMs(600),
                        EventScenarioAction.complete("afterB"),
                        EventScenarioAction.observe(),
                        EventScenarioAction.complete("dump"),
                        EventScenarioAction.complete("host")),
                List.of("host", "afterB", DUMP_JOB_TYPE), 5000,
                "Непрерывающая граница по сигналу: хост продолжает жить.");
    }

    private static EventScenario boundaryErrorInterruptingTask() {
        String bpmn = defs("f4BndErrInt", error("ErrTask", "f4-err-task", "E-TASK"),
                start("start", "f1")
                        + flow("f1", "start", "host")
                        + svc("host", "host", "f1", "f2")
                        + flow("f2", "host", "endMain")
                        + end("endMain", "f2")
                        + "<bpmn:boundaryEvent id=\"bnd\" name=\"bnd\" attachedToRef=\"host\" cancelActivity=\"true\">"
                        + "<bpmn:outgoing>fB</bpmn:outgoing>"
                        + "<bpmn:errorEventDefinition id=\"bed\" errorRef=\"ErrTask\" /></bpmn:boundaryEvent>"
                        + flow("fB", "bnd", "afterB")
                        + svc("afterB", "afterB", "fB", "fB2")
                        + flow("fB2", "afterB", "dump")
                        + svc("dump", DUMP_JOB_TYPE, "fB2", "f3")
                        + flow("f3", "dump", "end")
                        + end("end", "f3"));
        return scenario("f4-bnd-error-interrupting-task", "boundary",
                "boundaryEvent(error, прерывающая) на задаче", bpmn,
                EventScenario.StartMode.PLAIN, null,
                List.of(EventScenarioAction.throwError("host", "E-TASK"),
                        EventScenarioAction.waitMs(600),
                        EventScenarioAction.complete("afterB"),
                        EventScenarioAction.observe(),
                        EventScenarioAction.complete("dump")),
                List.of("host", "afterB", DUMP_JOB_TYPE), 5000,
                "Бросок BPMN-ошибки из сервисной задачи ловится границей ошибки.");
    }

    private static EventScenario boundaryTimerInterruptingContainer() {
        String bpmn = defs("f4BndTimerIntCont", "",
                start("start", "f1")
                        + flow("f1", "start", "sub")
                        + "<bpmn:subProcess id=\"sub\" name=\"sub\"><bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>"
                        + "<bpmn:startEvent id=\"subStart\"><bpmn:outgoing>fs1</bpmn:outgoing></bpmn:startEvent>"
                        + flow("fs1", "subStart", "subWork")
                        + svc("subWork", "subWork", "fs1", "fs2")
                        + flow("fs2", "subWork", "subEnd")
                        + end("subEnd", "fs2")
                        + "</bpmn:subProcess>"
                        + flow("f2", "sub", "endMain")
                        + end("endMain", "f2")
                        + "<bpmn:boundaryEvent id=\"bnd\" name=\"bnd\" attachedToRef=\"sub\" cancelActivity=\"true\">"
                        + "<bpmn:outgoing>fB</bpmn:outgoing>"
                        + "<bpmn:timerEventDefinition id=\"ted\"><bpmn:timeDuration xsi:type=\"bpmn:tFormalExpression\">PT1S</bpmn:timeDuration></bpmn:timerEventDefinition>"
                        + "</bpmn:boundaryEvent>"
                        + flow("fB", "bnd", "afterB")
                        + svc("afterB", "afterB", "fB", "fB2")
                        + flow("fB2", "afterB", "dump")
                        + svc("dump", DUMP_JOB_TYPE, "fB2", "f3")
                        + flow("f3", "dump", "end")
                        + end("end", "f3"));
        return scenario("f4-bnd-timer-interrupting-container", "boundary",
                "boundaryEvent(timer, прерывающая) на контейнере", bpmn,
                EventScenario.StartMode.PLAIN, null,
                List.of(EventScenarioAction.waitMs(1500),
                        EventScenarioAction.fireTimers(),
                        EventScenarioAction.waitMs(600),
                        EventScenarioAction.complete("afterB"),
                        EventScenarioAction.observe(),
                        EventScenarioAction.complete("dump")),
                List.of("subWork", "afterB", DUMP_JOB_TYPE), 8000,
                "Прерывающая граница по таймеру на ПОДПРОЦЕССЕ: подпроцесс и его содержимое гасятся.");
    }

    private static EventScenario boundaryTimerNonInterruptingContainer() {
        String bpmn = defs("f4BndTimerNonIntCont", "",
                start("start", "f1")
                        + flow("f1", "start", "sub")
                        + "<bpmn:subProcess id=\"sub\" name=\"sub\"><bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>"
                        + "<bpmn:startEvent id=\"subStart\"><bpmn:outgoing>fs1</bpmn:outgoing></bpmn:startEvent>"
                        + flow("fs1", "subStart", "subWork")
                        + svc("subWork", "subWork", "fs1", "fs2")
                        + flow("fs2", "subWork", "subEnd")
                        + end("subEnd", "fs2")
                        + "</bpmn:subProcess>"
                        + flow("f2", "sub", "endMain")
                        + end("endMain", "f2")
                        + "<bpmn:boundaryEvent id=\"bnd\" name=\"bnd\" attachedToRef=\"sub\" cancelActivity=\"false\">"
                        + "<bpmn:outgoing>fB</bpmn:outgoing>"
                        + "<bpmn:timerEventDefinition id=\"ted\"><bpmn:timeDuration xsi:type=\"bpmn:tFormalExpression\">PT1S</bpmn:timeDuration></bpmn:timerEventDefinition>"
                        + "</bpmn:boundaryEvent>"
                        + flow("fB", "bnd", "afterB")
                        + svc("afterB", "afterB", "fB", "fB2")
                        + flow("fB2", "afterB", "dump")
                        + svc("dump", DUMP_JOB_TYPE, "fB2", "f3")
                        + flow("f3", "dump", "end")
                        + end("end", "f3"));
        return scenario("f4-bnd-timer-noninterrupting-container", "boundary",
                "boundaryEvent(timer, непрерывающая) на контейнере", bpmn,
                EventScenario.StartMode.PLAIN, null,
                List.of(EventScenarioAction.waitMs(1500),
                        EventScenarioAction.fireTimers(),
                        EventScenarioAction.waitMs(600),
                        EventScenarioAction.complete("afterB"),
                        EventScenarioAction.observe(),
                        EventScenarioAction.complete("dump"),
                        EventScenarioAction.complete("subWork")),
                List.of("subWork", "afterB", DUMP_JOB_TYPE), 8000,
                "Непрерывающая граница по таймеру на подпроцессе: подпроцесс продолжает жить.");
    }

    private static EventScenario boundaryTimerOnMiContainer() {
        String bpmn = defs("f4BndTimerMi", "",
                start("start", "f1")
                        + flow("f1", "start", "miSub")
                        + "<bpmn:subProcess id=\"miSub\" name=\"miSub\"><bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>"
                        + "<bpmn:multiInstanceLoopCharacteristics isSequential=\"false\"><bpmn:extensionElements>"
                        + "<zeebe:loopCharacteristics inputCollection=\"=items\" inputElement=\"item\" />"
                        + "</bpmn:extensionElements></bpmn:multiInstanceLoopCharacteristics>"
                        + "<bpmn:startEvent id=\"subStart\"><bpmn:outgoing>fs1</bpmn:outgoing></bpmn:startEvent>"
                        + flow("fs1", "subStart", "subWork")
                        + svc("subWork", "subWork", "fs1", "fs2")
                        + flow("fs2", "subWork", "subEnd")
                        + end("subEnd", "fs2")
                        + "</bpmn:subProcess>"
                        + flow("f2", "miSub", "endMain")
                        + end("endMain", "f2")
                        + "<bpmn:boundaryEvent id=\"bnd\" name=\"bnd\" attachedToRef=\"miSub\" cancelActivity=\"true\">"
                        + "<bpmn:outgoing>fB</bpmn:outgoing>"
                        + "<bpmn:timerEventDefinition id=\"ted\"><bpmn:timeDuration xsi:type=\"bpmn:tFormalExpression\">PT1S</bpmn:timeDuration></bpmn:timerEventDefinition>"
                        + "</bpmn:boundaryEvent>"
                        + flow("fB", "bnd", "afterB")
                        + svc("afterB", "afterB", "fB", "fB2")
                        + flow("fB2", "afterB", "dump")
                        + svc("dump", DUMP_JOB_TYPE, "fB2", "f3")
                        + flow("f3", "dump", "end")
                        + end("end", "f3"));
        return scenario("f4-bnd-timer-on-mi-container", "boundary",
                "boundaryEvent(timer) на MI-контейнере (ENG-3: тело MI vs per-instance)", bpmn,
                "{\"items\":[\"a\",\"b\"]}", EventScenario.StartMode.PLAIN, null,
                List.of(EventScenarioAction.waitMs(1500),
                        EventScenarioAction.fireTimers(),
                        EventScenarioAction.waitMs(1000),
                        EventScenarioAction.complete("afterB"),
                        EventScenarioAction.observe(),
                        EventScenarioAction.complete("dump")),
                List.of("subWork", "afterB", DUMP_JOB_TYPE), 10000,
                "Граница на MI-контейнере: в Zeebe граница висит на ТЕЛЕ MI (срабатывает один раз "
                        + "на весь MI), у нас ENG-3 планирует per-instance — сверяем, что реально.");
    }

    private static EventScenario boundaryCycleTimer() {
        String bpmn = defs("f4BndCycle", "",
                start("start", "f1")
                        + flow("f1", "start", "host")
                        + svc("host", "host", "f1", "f2")
                        + flow("f2", "host", "endMain")
                        + end("endMain", "f2")
                        + "<bpmn:boundaryEvent id=\"bnd\" name=\"bnd\" attachedToRef=\"host\" cancelActivity=\"false\">"
                        + "<bpmn:outgoing>fB</bpmn:outgoing>"
                        + "<bpmn:timerEventDefinition id=\"ted\"><bpmn:timeCycle xsi:type=\"bpmn:tFormalExpression\">R/PT1S</bpmn:timeCycle></bpmn:timerEventDefinition>"
                        + "</bpmn:boundaryEvent>"
                        + flow("fB", "bnd", "afterB")
                        + svc("afterB", "afterB", "fB", "fB2")
                        + flow("fB2", "afterB", "dump")
                        + svc("dump", DUMP_JOB_TYPE, "fB2", "f3")
                        + flow("f3", "dump", "end")
                        + end("end", "f3"));
        return scenario("f4-bnd-cycle-timer", "timer", "boundaryEvent(timer/cycle, непрерывающая)", bpmn,
                EventScenario.StartMode.PLAIN, null,
                List.of(EventScenarioAction.waitMs(2500),
                        EventScenarioAction.fireTimers(),
                        EventScenarioAction.waitMs(800),
                        EventScenarioAction.complete("afterB"),
                        EventScenarioAction.observe(),
                        EventScenarioAction.complete("dump"),
                        EventScenarioAction.complete("host")),
                List.of("host", "afterB", DUMP_JOB_TYPE), 10000,
                "Циклическая граница: R/PT1S — граница должна перевзводиться (число срабатываний "
                        + "зависит от времени, поэтому оно НЕ прибивается).");
    }

    private static EventScenario boundaryDateTimer() {
        String bpmn = defs("f4BndDate", "",
                start("start", "f1")
                        + flow("f1", "start", "host")
                        + svc("host", "host", "f1", "f2")
                        + flow("f2", "host", "endMain")
                        + end("endMain", "f2")
                        + "<bpmn:boundaryEvent id=\"bnd\" name=\"bnd\" attachedToRef=\"host\" cancelActivity=\"true\">"
                        + "<bpmn:outgoing>fB</bpmn:outgoing>"
                        + "<bpmn:timerEventDefinition id=\"ted\"><bpmn:timeDate xsi:type=\"bpmn:tFormalExpression\">2020-01-01T00:00:00Z</bpmn:timeDate></bpmn:timerEventDefinition>"
                        + "</bpmn:boundaryEvent>"
                        + flow("fB", "bnd", "afterB")
                        + svc("afterB", "afterB", "fB", "fB2")
                        + flow("fB2", "afterB", "dump")
                        + svc("dump", DUMP_JOB_TYPE, "fB2", "f3")
                        + flow("f3", "dump", "end")
                        + end("end", "f3"));
        return scenario("f4-bnd-date-timer", "timer", "boundaryEvent(timer/date, прошедшая)", bpmn,
                EventScenario.StartMode.PLAIN, null,
                List.of(EventScenarioAction.fireTimers(),
                        EventScenarioAction.waitMs(800),
                        EventScenarioAction.complete("afterB"),
                        EventScenarioAction.observe(),
                        EventScenarioAction.complete("dump")),
                List.of("host", "afterB", DUMP_JOB_TYPE), 8000,
                "Граница по таймеру-дате в прошлом: срабатывает немедленно.");
    }

    // ------------------------------------------------------------------
    // КОНСТРУКЦИИ, КОТОРЫЕ ИНВЕНТАРЬ (ФАЗА 0) ОТМЕТИЛ КАК КАНДИДАТЫ ФАЗЫ 4
    // ------------------------------------------------------------------

    private static EventScenario endCompensate() {
        String bpmn = defs("f4EndCompensate", "",
                start("start", "f1")
                        + flow("f1", "start", "work")
                        + svc("work", "work", "f1", "f2")
                        + flow("f2", "work", "compEnd")
                        + "<bpmn:endEvent id=\"compEnd\"><bpmn:incoming>f2</bpmn:incoming>"
                        + "<bpmn:compensateEventDefinition id=\"ced\" activityRef=\"compHandler\" /></bpmn:endEvent>"
                        + "<bpmn:serviceTask id=\"compHandler\" name=\"compHandler\">"
                        + "<bpmn:extensionElements><zeebe:taskDefinition type=\"compHandler\" /></bpmn:extensionElements>"
                        + "</bpmn:serviceTask>");
        return scenario("f4-end-compensate", "end", "endEvent(compensate)", bpmn,
                EventScenario.StartMode.PLAIN, null,
                List.of(EventScenarioAction.complete("work"), EventScenarioAction.waitMs(800),
                        EventScenarioAction.observe()),
                List.of("work", "compHandler"), 6000,
                "End event с компенсацией: инвентарь (ФАЗА 0, §4-7) фиксирует, что на конце "
                        + "ветки компенсация У НАС НЕ ИСПОЛНЯЕТСЯ (тип элемента остаётся END_EVENT).");
    }

    private static EventScenario boundaryConditional() {
        String bpmn = defs("f4BndConditional", "",
                start("start", "f1")
                        + flow("f1", "start", "host")
                        + svc("host", "host", "f1", "f2")
                        + flow("f2", "host", "endMain")
                        + end("endMain", "f2")
                        + "<bpmn:boundaryEvent id=\"bnd\" name=\"bnd\" attachedToRef=\"host\" cancelActivity=\"true\">"
                        + "<bpmn:outgoing>fB</bpmn:outgoing>"
                        + "<bpmn:conditionalEventDefinition id=\"ced\"><bpmn:condition xsi:type=\"bpmn:tFormalExpression\">=flag</bpmn:condition></bpmn:conditionalEventDefinition>"
                        + "</bpmn:boundaryEvent>"
                        + flow("fB", "bnd", "afterB")
                        + svc("afterB", "afterB", "fB", "fB2")
                        + flow("fB2", "afterB", "dump")
                        + svc("dump", DUMP_JOB_TYPE, "fB2", "f3")
                        + flow("f3", "dump", "end")
                        + end("end", "f3"));
        return scenario("f4-bnd-conditional", "boundary", "boundaryEvent(conditional)", bpmn,
                EventScenario.StartMode.PLAIN, null,
                List.of(EventScenarioAction.waitMs(600),
                        EventScenarioAction.complete("afterB"),
                        EventScenarioAction.observe(),
                        EventScenarioAction.complete("dump")),
                List.of("host", "afterB", DUMP_JOB_TYPE), 6000,
                "Условная граница: Zeebe 8.5 условные события НЕ ПОДДЕРЖИВАЕТ (отказ на деплое), "
                        + "у нас поддерживаются — оракула по этому кейсу не существует.");
    }

    private static EventScenario boundaryWithoutEventDefinition() {
        String bpmn = defs("f4BndNoEventDef", "",
                start("start", "f1")
                        + flow("f1", "start", "host")
                        + svc("host", "host", "f1", "f2")
                        + flow("f2", "host", "endMain")
                        + end("endMain", "f2")
                        + "<bpmn:boundaryEvent id=\"bnd\" name=\"bnd\" attachedToRef=\"host\" cancelActivity=\"true\">"
                        + "<bpmn:outgoing>fB</bpmn:outgoing>"
                        + "</bpmn:boundaryEvent>"
                        + flow("fB", "bnd", "afterB")
                        + svc("afterB", "afterB", "fB", "fB2")
                        + flow("fB2", "afterB", "dump")
                        + svc("dump", DUMP_JOB_TYPE, "fB2", "f3")
                        + flow("f3", "dump", "end")
                        + end("end", "f3"));
        return scenario("f4-bnd-without-event-definition", "boundary",
                "boundaryEvent без event-definition (тихий дроп)", bpmn,
                EventScenario.StartMode.PLAIN, null,
                List.of(EventScenarioAction.waitMs(600),
                        EventScenarioAction.complete("afterB"),
                        EventScenarioAction.observe(),
                        EventScenarioAction.complete("dump")),
                List.of("host", "afterB", DUMP_JOB_TYPE), 6000,
                "Инвентарь (ФАЗА 0, §4-1): граница без исполняемого event-definition У НАС "
                        + "МОЛЧА ОТБРАСЫВАЕТСЯ (деплой проходит, граница не «взводится» никогда).");
    }

    private static EventScenario boundaryDanglingHost() {
        String bpmn = defs("f4BndDanglingHost", "",
                start("start", "f1")
                        + flow("f1", "start", "host")
                        + svc("host", "host", "f1", "f2")
                        + flow("f2", "host", "endMain")
                        + end("endMain", "f2")
                        + "<bpmn:boundaryEvent id=\"bnd\" name=\"bnd\" attachedToRef=\"noSuchHost\" cancelActivity=\"true\">"
                        + "<bpmn:outgoing>fB</bpmn:outgoing>"
                        + "<bpmn:timerEventDefinition id=\"ted\"><bpmn:timeDuration xsi:type=\"bpmn:tFormalExpression\">PT1S</bpmn:timeDuration></bpmn:timerEventDefinition>"
                        + "</bpmn:boundaryEvent>"
                        + flow("fB", "bnd", "afterB")
                        + svc("afterB", "afterB", "fB", "fB2")
                        + flow("fB2", "afterB", "dump")
                        + svc("dump", DUMP_JOB_TYPE, "fB2", "f3")
                        + flow("f3", "dump", "end")
                        + end("end", "f3"));
        return scenario("f4-bnd-dangling-host", "boundary",
                "boundaryEvent с attachedToRef на несуществующую activity", bpmn,
                EventScenario.StartMode.PLAIN, null,
                List.of(EventScenarioAction.waitMs(1500),
                        EventScenarioAction.fireTimers(),
                        EventScenarioAction.waitMs(600),
                        EventScenarioAction.complete("afterB"),
                        EventScenarioAction.observe(),
                        EventScenarioAction.complete("dump")),
                List.of("host", "afterB", DUMP_JOB_TYPE), 8000,
                "Инвентарь (ФАЗА 0, §4-18): граница, привязанная к НЕСУЩЕСТВУЮЩЕЙ activity, "
                        + "у нас деплоится без отказа и никогда не срабатывает.");
    }

    /**
     * Граница ошибки, ссылающаяся на НЕСУЩЕСТВУЮЩЕЕ определение ошибки (инвентарь §8, P-20).
     *
     * <p>Наш движок откатывает ref на «сырую строку» ({@code BpmnParseServiceImpl}: код
     * берётся как {@code registry.errorCodes().getOrDefault(ref, ref)}), поэтому граница
     * взводится с кодом, который ни одна реальная ошибка не породит.
     */
    private static EventScenario boundaryDanglingErrorRef() {
        String bpmn = defs("f4BndDanglingErr", "",
                start("start", "f1")
                        + flow("f1", "start", "host")
                        + svc("host", "host", "f1", "f2")
                        + flow("f2", "host", "endMain")
                        + end("endMain", "f2")
                        + "<bpmn:boundaryEvent id=\"bnd\" name=\"bnd\" attachedToRef=\"host\" cancelActivity=\"true\">"
                        + "<bpmn:outgoing>fB</bpmn:outgoing>"
                        + "<bpmn:errorEventDefinition id=\"bed\" errorRef=\"noSuchError\" /></bpmn:boundaryEvent>"
                        + flow("fB", "bnd", "afterB")
                        + svc("afterB", "afterB", "fB", "fB2")
                        + flow("fB2", "afterB", "dump")
                        + svc("dump", DUMP_JOB_TYPE, "fB2", "f3")
                        + flow("f3", "dump", "end")
                        + end("end", "f3"));
        return scenario("f4-bnd-dangling-error-ref", "boundary",
                "boundaryEvent(error) с errorRef на несуществующее определение", bpmn,
                EventScenario.StartMode.PLAIN, null,
                List.of(EventScenarioAction.throwError("host", "E-OTHER"),
                        EventScenarioAction.waitMs(800),
                        EventScenarioAction.complete("afterB"),
                        EventScenarioAction.observe(),
                        EventScenarioAction.complete("dump")),
                List.of("host", "afterB", DUMP_JOB_TYPE), 6000,
                "Инвентарь (ФАЗА 0, §8, P-20): error/signal/escalation ref на несуществующее "
                        + "определение — код откатывается на «сырой ref», событие никогда не сойдётся.");
    }

    /**
     * Camunda-экспортный тег {@code <bpmn:messageStartEvent>} (инвентарь §8, P-7).
     *
     * <p>Наша JAXB-модель биндит только {@code <bpmn:startEvent>} — экспортный тег в неё
     * не попадает, и процесс остаётся БЕЗ стартового события.
     */
    private static EventScenario camundaExportStartTag() {
        String bpmn = defs("f4CamundaExportTag",
                "<bpmn:message id=\"MsgExport\" name=\"f4-msg-export\" />",
                "<bpmn:messageStartEvent id=\"start\" name=\"start\">"
                        + "<bpmn:outgoing>f1</bpmn:outgoing>"
                        + "<bpmn:messageEventDefinition id=\"med\" messageRef=\"MsgExport\" /></bpmn:messageStartEvent>"
                        + flow("f1", "start", "dump")
                        + svc("dump", DUMP_JOB_TYPE, "f1", "f2")
                        + flow("f2", "dump", "end")
                        + end("end", "f2"));
        return scenario("f4-camunda-export-start-tag", "start",
                "Camunda-экспортный тег <bpmn:messageStartEvent>", bpmn,
                EventScenario.StartMode.SIGNAL, "f4-msg-export",
                List.of(EventScenarioAction.signal("f4-msg-export"),
                        EventScenarioAction.waitMs(800),
                        EventScenarioAction.observe(),
                        EventScenarioAction.complete("dump")),
                List.of(DUMP_JOB_TYPE), 5000,
                "Инвентарь (ФАЗА 0, §8, P-7): Camunda-экспортные теги (<messageStartEvent> и т. п.) "
                        + "наша JAXB-модель не биндит — модель молча теряет стартовое событие.");
    }

    // ------------------------------------------------------------------
    // ИНЦИДЕНТЫ И ОТМЕНА
    // ------------------------------------------------------------------

    private static EventScenario incidentResolve() {
        String bpmn = defs("f4IncidentResolve", error("ErrRes", "f4-err-res", "E-RES"),
                start("start", "f1")
                        + flow("f1", "start", "work")
                        + svc("work", "work", "f1", "f2")
                        + flow("f2", "work", "errEnd")
                        + "<bpmn:endEvent id=\"errEnd\"><bpmn:incoming>f2</bpmn:incoming>"
                        + "<bpmn:errorEventDefinition id=\"eed\" errorRef=\"ErrRes\" /></bpmn:endEvent>");
        return scenario("f4-incident-resolve", "incident", "error end → инцидент → resolve", bpmn,
                EventScenario.StartMode.PLAIN, null,
                List.of(EventScenarioAction.complete("work"),
                        EventScenarioAction.waitMs(800),
                        EventScenarioAction.resolveIncident(),
                        EventScenarioAction.waitMs(800),
                        EventScenarioAction.observe()),
                List.of("work"), 6000,
                "Инцидент на необработанной ошибке и его разбор: что происходит с потоком после resolve.");
    }

    private static EventScenario cancelInstance() {
        String bpmn = defs("f4CancelInstance", "",
                start("start", "f1")
                        + flow("f1", "start", "host")
                        + svc("host", "host", "f1", "f2")
                        + flow("f2", "host", "dump")
                        + svc("dump", DUMP_JOB_TYPE, "f2", "f3")
                        + flow("f3", "dump", "end")
                        + end("end", "f3"));
        return scenario("f4-cancel-instance", "cancel", "операторская отмена инстанса", bpmn,
                EventScenario.StartMode.PLAIN, null,
                List.of(EventScenarioAction.waitMs(500),
                        EventScenarioAction.cancelInstance(),
                        EventScenarioAction.waitMs(800),
                        EventScenarioAction.observe()),
                List.of("host", DUMP_JOB_TYPE), 6000,
                "Отмена инстанса на живой задаче: инстанс должен быть погашен, мусора не остаться.");
    }

    // ------------------------------------------------------------------
    // СБОРКА
    // ------------------------------------------------------------------

    private static EventScenario scenario(String id, String family, String element, String bpmn,
            EventScenario.StartMode startMode, String startSignal,
            List<EventScenarioAction> actions, List<String> jobTypes, long waitMs, String note) {
        return scenario(id, family, element, bpmn, "{}", startMode, startSignal,
                actions, jobTypes, waitMs, note);
    }

    /** Сценарий со стартовыми переменными (например — коллекция для MI). */
    private static EventScenario scenario(String id, String family, String element, String bpmn,
            String varsJson, EventScenario.StartMode startMode, String startSignal,
            List<EventScenarioAction> actions, List<String> jobTypes, long waitMs, String note) {
        return scenario(id, family, element, bpmn, varsJson, startMode, startSignal,
                actions, jobTypes, waitMs, note, "dump");
    }

    /** Сценарий с ЯВНОЙ точкой наблюдения (у части сценариев её id — «dump2»). */
    private static EventScenario scenario(String id, String family, String element, String bpmn,
            EventScenario.StartMode startMode, String startSignal,
            List<EventScenarioAction> actions, List<String> jobTypes, long waitMs, String note,
            String dumpElementId) {
        return scenario(id, family, element, bpmn, "{}", startMode, startSignal,
                actions, jobTypes, waitMs, note, dumpElementId);
    }

    /** Сценарий с ЯВНОЙ точкой наблюдения и стартовыми переменными. */
    private static EventScenario scenario(String id, String family, String element, String bpmn,
            String varsJson, EventScenario.StartMode startMode, String startSignal,
            List<EventScenarioAction> actions, List<String> jobTypes, long waitMs, String note,
            String dumpElementId) {
        String unique = uniqueBpmn(id, bpmn);
        return new EventScenario(id, family, element, unique, varsJson, actions,
                startMode, startSignal, dumpElementId, DUMP_JOB_TYPE + suffix(id),
                uniqueTypes(id, jobTypes), waitMs, volatileKeys(id), note, elementTypes(unique));
    }

    /**
     * Ключи наблюдения, которые НЕ прибиваются.
     *
     * <p>Циклические таймеры ({@code R/PT1S}) срабатывают столько раз, сколько успело
     * пройти окно ожидания, — это величина, зависящая от времени, а не от поведения.
     * Такие сценарии прибивают ФАКТ срабатывания, а число срабатываний живёт в отчёте
     * как измерение.
     */
    private static List<String> volatileKeys(String id) {
        if (id.contains("cycle")) {
            return List.of("elementCounts", "instanceCount");
        }
        if ("f4-start-signal".equals(id)) {
            // Число инстансов на один бросок сигнала зависит от числа подписанных
            // версий процесса НА СТЕНДЕ (Zeebe подписывает каждую версию; удаление
            // версии асинхронно) — это свойство стенда, а не сравниваемое поведение.
            return List.of("instanceCount", "elementCounts");
        }
        return List.of();
    }

    /** Суффикс типов job'ов сценария — у каждого сценария свои. */
    private static String suffix(String id) {
        return "-" + id;
    }

    /**
     * Типы job'ов уникальны на сценарий.
     *
     * <p>Иначе оракул (живой Zeebe) считает ЧУЖИЕ job'ы того же типа: активация в Zeebe
     * не фильтруется по инстансу, и «dump» от соседнего сценария/прогона попал бы в
     * счётчик элементов. Id ЭЛЕМЕНТОВ при этом остаются прежними («dump», «afterB») —
     * именно они сравниваются между движками.
     */
    private static List<String> uniqueTypes(String id, List<String> jobTypes) {
        return jobTypes.stream().map(t -> t + suffix(id)).toList();
    }

    /**
     * Та же BPMN, но с уникальными типами job'ов.
     *
     * <p>Меняются ТОЛЬКО типы заданий ({@code zeebe:taskDefinition type="..."}).
     * {@code xsi:type="bpmn:tFormalExpression"} НЕ трогается: это ссылка на XSD-тип,
     * и её порча ломает схему (поймано прогоном против живого Zeebe: «Cannot resolve
     * 'bpmn:tFormalExpression' to a type definition»).
     */
    private static String uniqueBpmn(String id, String bpmn) {
        return bpmn.replaceAll("zeebe:taskDefinition type=\"([^\"]+)\"",
                "zeebe:taskDefinition type=\"$1" + suffix(id) + "\"");
    }

    /**
     * Карта «id элемента → тип его job'а» — нужна оракулу, чтобы активировать ТОЛЬКО
     * нужный тип задания (иначе активация «всех типов» забирает чужие job'ы и прячет
     * факт «job остался жив в движке»).
     */
    private static Map<String, String> elementTypes(String bpmn) {
        Map<String, String> out = new LinkedHashMap<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<bpmn:serviceTask id=\"([^\"]+)\"[^>]*>.*?zeebe:taskDefinition type=\"([^\"]+)\"",
                        java.util.regex.Pattern.DOTALL)
                .matcher(bpmn);
        while (m.find()) {
            out.put(m.group(1), m.group(2));
        }
        return out;
    }

    private static String defs(String processId, String extraDefs, String body) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
                + "xmlns:zeebe=\"http://camunda.org/schema/zeebe/1.0\" "
                + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
                + "id=\"Definitions_" + processId + "\" targetNamespace=\"http://bpmn.io/schema/bpmn\">\n"
                + extraDefs
                + "<bpmn:process id=\"" + processId + "\" name=\"" + processId + "\" isExecutable=\"true\">\n"
                + body
                + "</bpmn:process>\n"
                + "</bpmn:definitions>\n";
    }

    private static String start(String id, String outgoing) {
        return start(id, outgoing, "");
    }

    /** Start event с event definition (пустая строка — обычный none start). */
    private static String start(String id, String outgoing, String eventDefinition) {
        return "<bpmn:startEvent id=\"" + id + "\" name=\"" + id + "\"><bpmn:outgoing>"
                + outgoing + "</bpmn:outgoing>" + eventDefinition + "</bpmn:startEvent>";
    }

    private static String end(String id, String incoming) {
        return "<bpmn:endEvent id=\"" + id + "\" name=\"" + id + "\"><bpmn:incoming>"
                + incoming + "</bpmn:incoming></bpmn:endEvent>";
    }

    private static String flow(String id, String source, String target) {
        return "<bpmn:sequenceFlow id=\"" + id + "\" sourceRef=\"" + source + "\" targetRef=\"" + target + "\" />";
    }

    private static String svc(String id, String type, String incoming, String outgoing) {
        return "<bpmn:serviceTask id=\"" + id + "\" name=\"" + id + "\"><bpmn:extensionElements>"
                + "<zeebe:taskDefinition type=\"" + type + "\" /></bpmn:extensionElements>"
                + "<bpmn:incoming>" + incoming + "</bpmn:incoming><bpmn:outgoing>" + outgoing
                + "</bpmn:outgoing></bpmn:serviceTask>";
    }

    private static String signal(String id, String name) {
        return "<bpmn:signal id=\"" + id + "\" name=\"" + name + "\" />";
    }

    private static String error(String id, String name, String code) {
        return "<bpmn:error id=\"" + id + "\" name=\"" + name + "\" errorCode=\"" + code + "\" />";
    }

    private static String escalation(String id, String name, String code) {
        return "<bpmn:escalation id=\"" + id + "\" name=\"" + name + "\" escalationCode=\"" + code + "\" />";
    }

    /** Подпроцесс с error end event внутри (обработчик — граница снаружи). */
    private static String subProcessError(String id, String inFlow, String outFlow, String errorRef) {
        return "<bpmn:subProcess id=\"" + id + "\" name=\"" + id + "\"><bpmn:incoming>" + inFlow
                + "</bpmn:incoming><bpmn:outgoing>" + outFlow + "</bpmn:outgoing>"
                + "<bpmn:startEvent id=\"" + id + "Start\"><bpmn:outgoing>fs1</bpmn:outgoing></bpmn:startEvent>"
                + flow("fs1", id + "Start", id + "Err")
                + "<bpmn:endEvent id=\"" + id + "Err\"><bpmn:incoming>fs1</bpmn:incoming>"
                + "<bpmn:errorEventDefinition id=\"" + id + "Eed\" errorRef=\"" + errorRef + "\" /></bpmn:endEvent>"
                + "</bpmn:subProcess>";
    }

    /** Подпроцесс с escalation end event внутри. */
    private static String subProcessEscalation(String id, String inFlow, String outFlow, String escRef) {
        return "<bpmn:subProcess id=\"" + id + "\" name=\"" + id + "\"><bpmn:incoming>" + inFlow
                + "</bpmn:incoming><bpmn:outgoing>" + outFlow + "</bpmn:outgoing>"
                + "<bpmn:startEvent id=\"" + id + "Start\"><bpmn:outgoing>fs1</bpmn:outgoing></bpmn:startEvent>"
                + flow("fs1", id + "Start", id + "Esc")
                + "<bpmn:endEvent id=\"" + id + "Esc\"><bpmn:incoming>fs1</bpmn:incoming>"
                + "<bpmn:escalationEventDefinition id=\"" + id + "Eed\" escalationRef=\"" + escRef + "\" /></bpmn:endEvent>"
                + "</bpmn:subProcess>";
    }
}
