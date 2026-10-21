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
 * @param publishes    исходы публикаций сообщений (срез F2, WO-OPS-28): по
 *                     одному на публикацию, в формате {@code <имя>:correlated=<n>} —
 *                     сколько подписок публикация разбудила. У оракула тот же
 *                     факт виден как «публикация отвергнута» (нет подписчика) —
 *                     обе стороны нормализуются в одно поле при сравнении.
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
        String error,
        List<String> publishes) {

    /** Сценарий среза F1 (WO-OPS-27) — без публикаций. */
    public ScenarioOutcome(String scenarioId, String container, String content, int cardinality,
            String order, String status, Map<String, String> rootVars, List<String> leaks,
            List<String> incidents, String error) {
        this(scenarioId, container, content, cardinality, order, status, rootVars, leaks, incidents,
                error, List.of());
    }

    public boolean completed() {
        return "COMPLETED".equals(status);
    }
}
