package com.zorrodev.bpm.engine.scenario.event;

import java.util.List;
import java.util.Map;

/**
 * WO-OPS-28 ФАЗА 4: наблюдение по одному сценарию среза «события».
 *
 * @param scenarioId id сценария
 * @param family     семейство (ось отчёта)
 * @param element    BPMN-элемент под проверкой
 * @param status     статус инстанса на конец прогона
 *                   (COMPLETED / CANCELLED / NOT_COMPLETED / NO_INSTANCE / FAILED)
 * @param observation карта наблюдений (dumpReached, vars, liveElements, instanceStatus, …)
 * @param leaks      нарушения инварианта утечек (WO-OPS-26) по этому инстансу
 * @param incidents  сообщения открытых инцидентов инстанса
 * @param error      текст ошибки прогона (пусто = ошибок нет)
 */
public record EventScenarioOutcome(
        String scenarioId,
        String family,
        String element,
        String status,
        Map<String, Object> observation,
        List<String> leaks,
        List<String> incidents,
        String error) {

    /** Наблюдение «точка наблюдения достигнута». */
    public boolean dumpReached() {
        return Boolean.TRUE.equals(observation.get("dumpReached"));
    }
}
