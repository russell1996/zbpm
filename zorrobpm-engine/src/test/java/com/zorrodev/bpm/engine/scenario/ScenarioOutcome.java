package com.zorrodev.bpm.engine.scenario;

import java.util.List;
import java.util.Map;

/**
 * WO-OPS-27: результат прогона одного сценария на нашем движке.
 *
 * @param scenarioId   идентификатор сценария
 * @param container    ось «контейнер»
 * @param content      ось «содержимое»
 * @param cardinality  размер коллекции
 * @param order        порядок завершения (через запятую)
 * @param status       терминальный статус инстанса: {@code COMPLETED} / {@code NOT_COMPLETED} / {@code FAILED}
 * @param rootVars     наблюдаемые переменные корневого скоупа (имя → сырое значение)
 * @param leaks        нарушения инварианта утечек (WO-OPS-26) по этому инстансу
 * @param incidents    сообщения инцидентов инстанса (пусто = чисто)
 * @param error        текст ошибки прогона (пусто = ошибок нет)
 */
public record ScenarioOutcome(
        String scenarioId,
        String container,
        String content,
        int cardinality,
        String order,
        String status,
        Map<String, String> rootVars,
        List<String> leaks,
        List<String> incidents,
        String error) {

    public boolean completed() {
        return "COMPLETED".equals(status);
    }
}
