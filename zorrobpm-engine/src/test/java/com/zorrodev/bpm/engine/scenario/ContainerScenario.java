package com.zorrodev.bpm.engine.scenario;

import java.util.List;

/**
 * WO-OPS-28 ФАЗА 3: один сценарий среза «контейнеры» (F3).
 *
 * <p>Сценарий — это НАБЛЮДАЕМЫЙ КОНТЕЙНЕР-СЛЕД: описание прогона, одинаковое
 * для нашего движка и для живого Zeebe 8.5.0 (тот же список шагов выполняется
 * обеими сторонами, иначе сравнение было бы нечестным).
 *
 * <p>Базовые поля (схема, стартовые переменные, элементы, задача-наблюдатель)
 * лежат в {@link BpmnScenario} — это ТОТ ЖЕ тип сценариев, что и в срезе
 * F1 (WO-OPS-27) и F2 (WO-OPS-28 фаза 2): переиспользуем формат, чтобы
 * артефакты прогона и оракула читались одинаково. F3 добавляет три вещи,
 * которых в F1/F2 не было:
 *
 * <ul>
 *   <li><b>дополнительные ресурсы</b> ({@code childBpmn}) — дочерний процесс для
 *       call activity (у нас и в Zeebe дочерний процесс — ОТДЕЛЬНЫЙ деплой);</li>
 *   <li><b>шаги вождения</b> ({@code steps}) — у каждого шага свой тип job'а
 *       (для оракула) и свой id элемента (для нас), свои переменныеcompletion'а
 *       и необязательный фильтр по значению MI-цикла ({@code item}) — так
 *       отличается «завершить ВСЕ инстансы» от «завершить ровно один»;</li>
 *   <li><b>ожидание</b> ({@code expectation}) — человекочитаемое описание того,
 *       что сценарий ПРОВЕРЯЕТ (для таблицы отчёта; не влияет на прогон).</li>
 * </ul>
 *
 * @param base       базовый сценарий в формате среза F1 (BPMN, переменные, элементы)
 * @param childBpmn  дочерние ресурсы, которые надо задеплоить ДО основного (call activity)
 * @param steps      шаги вождения (в порядке выполнения)
 * @param expectation что проверяет сценарий (для отчёта)
 */
public record ContainerScenario(
        BpmnScenario base,
        List<String> childBpmn,
        List<Step> steps,
        String expectation) {

    /**
     * Один шаг вождения.
     *
     * @param elementId id элемента BPMN (наша сторона — activity по этому id)
     * @param jobType   тип job'а (сторона оракула Zeebe — активация по типу)
     * @param varsJson  JSON-объект переменных, которые записываются при завершении
     * @param item      если задано — завершается ТОЛЬКО тот инстанс, у которого
     *                  переменная цикла {@code item} равна этому значению
     */
    public record Step(String elementId, String jobType, String varsJson, String item) {

        public Step(String elementId, String jobType) {
            this(elementId, jobType, "{}", null);
        }

        public Step(String elementId, String jobType, String varsJson) {
            this(elementId, jobType, varsJson, null);
        }

        /** Шаг без фильтра по инстансу MI-цикла. */
        public boolean anyInstance() {
            return item == null || item.isBlank();
        }
    }

    public String id() {
        return base.id();
    }

    public String content() {
        return base.content();
    }

    public String bpmn() {
        return base.bpmn();
    }

    public String varsJson() {
        return base.varsJson();
    }

    public String dumpElementId() {
        return base.dumpElementId();
    }

    public String dumpJobType() {
        return base.dumpJobType();
    }

    public List<String> taskElementIds() {
        return base.taskElementIds();
    }

    public List<String> userTaskElementIds() {
        return base.userTaskElementIds();
    }

    public List<String> items() {
        return base.items();
    }

    public int cardinality() {
        return base.cardinality();
    }
}
