package com.zorrodev.bpm.engine.scenario.event;

/**
 * WO-OPS-28 ФАЗА 4: одно действие сценария среза «события» (F4).
 *
 * <p>Сценарий среза F4 — это НЕ «список задач, которые надо завершить» (как в F1) и не
 * «маршрут по шлюзу» (как в F2). Здесь сценарий — это <b>последовательность внешних
 * событий</b>, которыми двигатель приводится в нужное состояние: публикация сигнала,
 * бросок ошибки из задачи, срабатывание таймера, отмена инстанса, разбор инцидента.
 *
 * <p>Один и тот же список действий выполняют оба движка — наш и живой Zeebe 8.5, — поэтому
 * сравнение остаётся честным (та же схема, те же значения, тот же порядок действий).
 * Действия, которые в Zeebe делает сам брокер (таймеры), помечены как {@link #FIRE_TIMERS}:
 * на нашей стороне это вызов боевого {@code TimerBatchProcessor.processBatch()} (тот же
 * путь, что у планировщика), на стороне оракула — ожидание штатного срабатывания.
 *
 * @param kind    вид действия (см. константы {@code COMPLETE…OBSERVE})
 * @param target  цель действия: id элемента (для {@link #COMPLETE}, {@link #THROW_ERROR}),
 *                имя сигнала (для {@link #BROADCAST_SIGNAL}); пусто — цель не нужна
 * @param varsJson JSON-объект переменных, которые записываются при действии
 * @param ms      длительность ожидания (только для {@link #WAIT})
 * @param code    код ошибки (только для {@link #THROW_ERROR})
 */
public record EventScenarioAction(String kind, String target, String varsJson, long ms, String code) {

    /** Завершить живую задачу с указанным id элемента. */
    public static final String COMPLETE = "complete";
    /** Разослать сигнал (будит catch/границу/старт по сигналу). */
    public static final String BROADCAST_SIGNAL = "broadcastSignal";
    /** Подождать (ms) — нужно, чтобы доехали таймеры/подписки. */
    public static final String WAIT = "wait";
    /** Выстрелить все просроченные таймеры (наша сторона) / дождаться их (оракул). */
    public static final String FIRE_TIMERS = "fireTimers";
    /** Бросить BPMN-ошибку из сервисной задачи (для границы ошибки). */
    public static final String THROW_ERROR = "throwError";
    /** Разобрать первый открытый инцидент инстанса. */
    public static final String RESOLVE_INCIDENT = "resolveIncident";
    /** Отменить инстанс (операторская отмена). */
    public static final String CANCEL_INSTANCE = "cancelInstance";
    /** Точка наблюдения: снять состояние процесса. */
    public static final String OBSERVE = "observe";

    /** Действие без vars и без цели. */
    public EventScenarioAction(String kind, String target) {
        this(kind, target, null, 0L, null);
    }

    /** Действие с целевой id элемента. */
    public static EventScenarioAction complete(String elementId) {
        return new EventScenarioAction(COMPLETE, elementId);
    }

    /** Действие с целевой id элемента и переменными. */
    public static EventScenarioAction complete(String elementId, String varsJson) {
        return new EventScenarioAction(COMPLETE, elementId, varsJson, 0L, null);
    }

    /** Разослать сигнал. */
    public static EventScenarioAction signal(String signalName) {
        return new EventScenarioAction(BROADCAST_SIGNAL, signalName);
    }

    /** Подождать указанное число миллисекунд. */
    public static EventScenarioAction waitMs(long ms) {
        return new EventScenarioAction(WAIT, null, null, ms, null);
    }

    /** Выстрелить просроченные таймеры. */
    public static EventScenarioAction fireTimers() {
        return new EventScenarioAction(FIRE_TIMERS, null);
    }

    /** Бросить ошибку с кодом из сервисной задачи. */
    public static EventScenarioAction throwError(String elementId, String errorCode) {
        return new EventScenarioAction(THROW_ERROR, elementId, null, 0L, errorCode);
    }

    /** Разобрать первый открытый инцидент. */
    public static EventScenarioAction resolveIncident() {
        return new EventScenarioAction(RESOLVE_INCIDENT, null);
    }

    /** Отменить инстанс. */
    public static EventScenarioAction cancelInstance() {
        return new EventScenarioAction(CANCEL_INSTANCE, null);
    }

    /** Точка наблюдения. */
    public static EventScenarioAction observe() {
        return new EventScenarioAction(OBSERVE, null);
    }
}
