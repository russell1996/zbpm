package com.zorrodev.bpm.engine.scenario;

import java.util.List;

/**
 * WO-OPS-27: один сгенерированный BPMN-сценарий.
 *
 * <p>Сценарий — это полное описание прогона: сама BPMN-схема (строка XML, одинаковая
 * для нашего движка и для оракула Zeebe 8.5), стартовые переменные и порядок завершения
 * инстансов MI-контейнера.
 *
 * @param id          стабильный идентификатор сценария (ключ во всех отчётах)
 * @param container   ось «контейнер» (см. {@link BpmnScenarioGenerator.Container})
 * @param content     ось «содержимое» (см. {@link BpmnScenarioGenerator.Content})
 * @param cardinality размер коллекции MI (0/1/2/3 — границы оси)
 * @param items       элементы коллекции (входной порядок)
 * @param order       порядок завершения инстансов (перестановка {@code items})
 * @param bpmn        BPMN 2.0 XML (Zeebe-совместимый диалект)
 * @param varsJson    стартовые переменные (JSON-объект)
 * @param taskElementIds id элементов, которые нужно завершать (шаги внутри MI-тела)
 * @param dumpElementId id «наблюдательной» задачи (её job payload = наблюдаемое состояние)
 * @param steps сколько job'ов приходится на один инстанс MI-тела (1 или 2 — цепочка)
 * @param userTaskElementIds id элементов, которые завершаются как USER TASK
 * @param jobTypes типы job'ов рабочих задач (по ним оракул Zeebe активирует джобы)
 * @param dumpJobType тип job'а «наблюдательной» задачи
 */
public record BpmnScenario(
        String id,
        String container,
        String content,
        int cardinality,
        List<String> items,
        List<String> order,
        String bpmn,
        String varsJson,
        List<String> taskElementIds,
        String dumpElementId,
        int steps,
        List<String> userTaskElementIds,
        List<String> jobTypes,
        String dumpJobType) {

    /** Ось «порядок завершения» в каноническом виде — для отчётов и группировок. */
    public String orderKey() {
        return String.join(",", order);
    }
}
