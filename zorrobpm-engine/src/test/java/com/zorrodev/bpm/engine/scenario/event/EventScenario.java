package com.zorrodev.bpm.engine.scenario.event;

import java.util.List;

/**
 * WO-OPS-28 ФАЗА 4: один сценарий среза «события» (F4).
 *
 * <p>Сценарий — полное описание прогона: сама BPMN-схема (одна и та же строка XML для
 * нашего движка и для оракула Zeebe 8.5), стартовые переменные, способ старта и
 * последовательность действий.
 *
 * @param id            стабильный идентификатор сценария (ключ во всех отчётах)
 * @param family        семейство сценариев (ось отчёта: start/end/intermediate/boundary/…)
 * @param element       BPMN-элемент под проверкой (например {@code startEvent+signal})
 * @param bpmn          BPMN 2.0 XML (Zeebe-совместимый диалект)
 * @param varsJson      стартовые переменные (JSON-объект)
 * @param actions       последовательность действий (см. {@link EventScenarioAction})
 * @param startMode     способ старта инстанса
 * @param startSignal   имя сигнала для старта (только {@link StartMode#SIGNAL})
 * @param dumpElementId id элемента точки наблюдения (пусто — наблюдать нечего)
 * @param dumpJobType   тип job'а точки наблюдения
 * @param jobTypes      типы job'ов, которые активирует оракул (для завершения задач)
 * @param waitMs        сколько ждать появления точки наблюдения
 * @param volatileKeys  ключи наблюдения, которые НЕ прибиваются (зависят от времени)
 * @param note          человекочитаемая пометка о сценарии
 * @param elementTypes  карта «id элемента → тип job'а» (оракулу нужно, чтобы активировать
 *                      ТОЛЬКО нужный тип задания и не забрать чужие)
 */
public record EventScenario(
        String id,
        String family,
        String element,
        String bpmn,
        String varsJson,
        List<EventScenarioAction> actions,
        StartMode startMode,
        String startSignal,
        String dumpElementId,
        String dumpJobType,
        List<String> jobTypes,
        long waitMs,
        List<String> volatileKeys,
        String note,
        java.util.Map<String, String> elementTypes) {

    /** Способ старта инстанса сценария. */
    public enum StartMode {
        /** Обычный (none) start event: инстанс стартует вызовом. */
        PLAIN,
        /** Start event по сигналу: инстанс создаёт БРОСОК сигнала. */
        SIGNAL,
        /** Start event по таймеру: инстанс создаёт СРАБОТАВШИЙ таймер. */
        TIMER
    }
}
