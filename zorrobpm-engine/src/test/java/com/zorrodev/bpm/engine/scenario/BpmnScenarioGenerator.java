package com.zorrodev.bpm.engine.scenario;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * WO-OPS-27: генератор матрицы BPMN-сценариев.
 *
 * <p>Детерминированный (без случайности, без внешних зависимостей): одна и та же матрица
 * получается при каждом запуске, поэтому её можно сравнивать с замером оракула Zeebe 8.5.
 *
 * <p>Оси (срез F1 — «контейнер × содержимое × размер коллекции × порядок завершения»):
 * <ul>
 *   <li><b>контейнер</b>: MI на service task (parallel/sequential) и MI-подпроцесс
 *       (parallel/sequential);</li>
 *   <li><b>содержимое</b>: одиночная service task внутри MI-тела либо цепочка
 *       service→service;</li>
 *   <li><b>размер коллекции</b>: 0, 1, 2, 3 (границы оси);</li>
 *   <li><b>порядок завершения</b>: ВСЕ перестановки элементов коллекции (для N≤3);
 *       для sequential-контейнера порядок вынужденный (жив ровно один инстанс), поэтому
 *       генерируется только естественный порядок — иначе сценарий невыполним.</li>
 * </ul>
 *
 * <p>Схема deliberately собирается в Zeebe-совместимом диалекте: те же атрибуты
 * {@code zeebe:loopCharacteristics}, тот же {@code zeebe:taskDefinition}, те же id.
 * Иначе «дифференциальный прогон» сравнивал бы разные модели, а не поведение.
 */
public final class BpmnScenarioGenerator {

    /** Ось «контейнер». */
    public enum Container {
        /** MI на service task, параллельно. */
        MI_PARALLEL_TASK,
        /** MI на service task, последовательно. */
        MI_SEQUENTIAL_TASK,
        /** MI-подпроцесс, параллельно. */
        MI_PARALLEL_SUBPROCESS,
        /** MI-подпроцесс, последовательно. */
        MI_SEQUENTIAL_SUBPROCESS;

        public boolean parallel() {
            return this == MI_PARALLEL_TASK || this == MI_PARALLEL_SUBPROCESS;
        }

        public boolean onSubProcess() {
            return this == MI_PARALLEL_SUBPROCESS || this == MI_SEQUENTIAL_SUBPROCESS;
        }
    }

    /** Ось «содержимое» MI-тела. */
    public enum Content {
        /** Одна service task. */
        SINGLE,
        /** Цепочка service→service (два шага на инстанс). */
        CHAIN,
        /** Одна user task (assignee резолвится из inputElement в скоупе инстанса). */
        USER;

        public int steps() {
            return this == CHAIN ? 2 : 1;
        }
    }

    /** Имя переменной коллекции (вход MI). */
    public static final String COLLECTION_VAR = "items";
    /** Имя переменной элемента коллекции (inputElement). */
    public static final String INPUT_ELEMENT = "item";
    /** Имя переменной результата (outputCollection). */
    public static final String OUTPUT_COLLECTION = "results";
    /** Тип job'а рабочей задачи. */
    public static final String WORK_JOB_TYPE = "work";
    /** Тип job'а второго шага цепочки. */
    public static final String WORK2_JOB_TYPE = "work2";
    /** Тип job'а «наблюдательной» задачи (её payload = наблюдаемое состояние). */
    public static final String DUMP_JOB_TYPE = "dump";
    /**
     * Тип job'а user task в Zeebe 8.5.0.
     *
     * <p><b>Измерено живьём, а не взято из памяти</b> (Zeebe 8.5.0, проба
     * {@code docs/analysis/c8-oracle/user-task-probe.js}): user task в 8.5 —
     * это job типа {@code io.camunda.zeebe:userTask}. Тип 8.4
     * ({@code io.camunda:user-task}) в 8.5 НЕ используется: проба перебирает
     * 7 правдоподобных типов и находит job только у {@code io.camunda.zeebe:userTask}.
     *
     * <p>Отдельно измерено: {@code <zeebe:userTask/>} (нативный user task 8.5)
     * job-worker API НЕ отдаёт вообще (у такого user task'а нет активируемого
     * job'а) — поэтому оракул для оси user task разворачивается в job-worker
     * диалекте (см. {@code scenario-matrix.js}, {@code toZeebeDialect}).
     */
    public static final String USER_TASK_JOB_TYPE = "io.camunda.zeebe:userTask";

    private BpmnScenarioGenerator() {
    }

    /** Полная матрица среза F1. */
    public static List<BpmnScenario> generate() {
        List<BpmnScenario> out = new ArrayList<>();
        for (Container container : Container.values()) {
            for (Content content : contentsFor(container)) {
                for (int cardinality = 0; cardinality <= 3; cardinality++) {
                    List<String> items = itemsFor(cardinality);
                    for (List<String> order : ordersFor(container, items)) {
                        out.add(build(container, content, items, order));
                    }
                }
            }
        }
        return out;
    }

    /** MI на задаче не может содержать цепочку — там «тело» = сама задача. */
    private static List<Content> contentsFor(Container container) {
        return container.onSubProcess()
                ? List.of(Content.SINGLE, Content.CHAIN, Content.USER)
                : List.of(Content.SINGLE);
    }

    /** Элементы коллекции для заданного размера (детерминированные). */
    public static List<String> itemsFor(int cardinality) {
        return switch (cardinality) {
            case 0 -> List.of();
            case 1 -> List.of("a");
            case 2 -> List.of("a", "b");
            case 3 -> List.of("a", "b", "c");
            default -> throw new IllegalArgumentException("cardinality out of the slice: " + cardinality);
        };
    }

    /**
     * Все перестановки элементов коллекции (порядок завершения инстансов).
     * Для sequential-контейнера — только естественный порядок: жив ровно один инстанс,
     * «переставить» нечего.
     */
    public static List<List<String>> ordersFor(Container container, List<String> items) {
        if (!container.parallel() || items.size() <= 1) {
            return List.of(items);
        }
        List<List<String>> out = new ArrayList<>();
        permute(new ArrayList<>(items), 0, out);
        return out;
    }

    private static void permute(List<String> current, int from, List<List<String>> out) {
        if (from == current.size()) {
            out.add(List.copyOf(current));
            return;
        }
        for (int i = from; i < current.size(); i++) {
            Collections.swap(current, from, i);
            permute(current, from + 1, out);
            Collections.swap(current, from, i);
        }
    }

    private static BpmnScenario build(Container container, Content content,
            List<String> items, List<String> order) {
        String id = container.name().toLowerCase() + "-" + content.name().toLowerCase()
                + "-n" + items.size() + "-" + String.join("", order);
        String bpmn = bpmn(container, content);
        List<String> taskIds = taskElementIds(container, content);
        List<String> userTaskIds = userTaskElementIds(container, content);
        List<String> jobTypes = jobTypes(container, content);
        String varsJson = "{\"" + COLLECTION_VAR + "\":" + jsonArray(items) + "}";
        return new BpmnScenario(id, container.name(), content.name(), items.size(),
                items, order, bpmn, varsJson, taskIds, "dump", content.steps(),
                userTaskIds, jobTypes, DUMP_JOB_TYPE);
    }

    private static List<String> taskElementIds(Container container, Content content) {
        if (!container.onSubProcess()) {
            return List.of("miTask");
        }
        if (content == Content.CHAIN) {
            return List.of("work", "work2");
        }
        if (content == Content.USER) {
            return List.of("utProbe");
        }
        return List.of("work");
    }

    /** Элементы, которые завершаются как USER TASK (у них другой путь завершения). */
    private static List<String> userTaskElementIds(Container container, Content content) {
        return container.onSubProcess() && content == Content.USER ? List.of("utProbe") : List.of();
    }

    /** Типы job'ов, которые активирует оракул (Zeebe) для завершения MI-тела. */
    private static List<String> jobTypes(Container container, Content content) {
        if (!container.onSubProcess()) {
            return List.of(WORK_JOB_TYPE);
        }
        if (content == Content.CHAIN) {
            return List.of(WORK_JOB_TYPE, WORK2_JOB_TYPE);
        }
        if (content == Content.USER) {
            // Zeebe 8.5.0: user task — job типа io.camunda.zeebe:userTask
            // (измерено пробой docs/analysis/c8-oracle/user-task-probe.js).
            return List.of(USER_TASK_JOB_TYPE);
        }
        return List.of(WORK_JOB_TYPE);
    }

    private static String jsonArray(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append('"').append(items.get(i)).append('"');
        }
        return sb.append("]").toString();
    }

    /** BPMN 2.0 (Zeebe-совместимый диалект) для пары (контейнер, содержимое). */
    public static String bpmn(Container container, Content content) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" ")
          .append("xmlns:zeebe=\"http://camunda.org/schema/zeebe/1.0\" ")
          .append("id=\"defs_ops27\" targetNamespace=\"http://bpmn.io/schema/bpmn\">\n");
        sb.append("  <bpmn:process id=\"ops27\" name=\"ops27\" isExecutable=\"true\">\n");
        sb.append("    <bpmn:startEvent id=\"start\">\n")
          .append("      <bpmn:outgoing>f1</bpmn:outgoing>\n")
          .append("    </bpmn:startEvent>\n");
        sb.append("    <bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"")
          .append(container.onSubProcess() ? "miSub" : "miTask").append("\" />\n");

        if (container.onSubProcess()) {
            sb.append(miSubProcess(container, content));
        } else {
            sb.append(miTask(container));
        }

        sb.append("    <bpmn:sequenceFlow id=\"f2\" sourceRef=\"")
          .append(container.onSubProcess() ? "miSub" : "miTask").append("\" targetRef=\"dump\" />\n");
        sb.append("    <bpmn:serviceTask id=\"dump\" name=\"dump\">\n")
          .append("      <bpmn:extensionElements>\n")
          .append("        <zeebe:taskDefinition type=\"").append(DUMP_JOB_TYPE).append("\" />\n")
          .append("      </bpmn:extensionElements>\n")
          .append("      <bpmn:incoming>f2</bpmn:incoming>\n")
          .append("      <bpmn:outgoing>f6</bpmn:outgoing>\n")
          .append("    </bpmn:serviceTask>\n");
        sb.append("    <bpmn:sequenceFlow id=\"f6\" sourceRef=\"dump\" targetRef=\"end\" />\n");
        sb.append("    <bpmn:endEvent id=\"end\">\n")
          .append("      <bpmn:incoming>f6</bpmn:incoming>\n")
          .append("    </bpmn:endEvent>\n");
        sb.append("  </bpmn:process>\n");
        sb.append("</bpmn:definitions>\n");
        return sb.toString();
    }

    private static String miTask(Container container) {
        return "    <bpmn:serviceTask id=\"miTask\" name=\"miTask\">\n"
                + "      <bpmn:extensionElements>\n"
                + "        <zeebe:taskDefinition type=\"" + WORK_JOB_TYPE + "\" />\n"
                + "      </bpmn:extensionElements>\n"
                + "      <bpmn:incoming>f1</bpmn:incoming>\n"
                + "      <bpmn:outgoing>f2</bpmn:outgoing>\n"
                + loopCharacteristics(container)
                + "    </bpmn:serviceTask>\n";
    }

    private static String miSubProcess(Container container, Content content) {
        StringBuilder sb = new StringBuilder();
        sb.append("    <bpmn:subProcess id=\"miSub\" name=\"miSub\">\n");
        // Порядок элементов — как в проверенных на Zeebe 8.5 фикстурах C8-41
        // (docs/analysis/c8-oracle/bpmn/s1-mi-sub-race.bpmn): incoming/outgoing
        // ПЕРЕД multiInstanceLoopCharacteristics. Иначе XSD-валидатор Zeebe
        // отвергает схему («One of laneSet, flowElement, artifact is expected»).
        sb.append("      <bpmn:incoming>f1</bpmn:incoming>\n");
        sb.append("      <bpmn:outgoing>f2</bpmn:outgoing>\n");
        sb.append(loopCharacteristics(container));
        sb.append("      <bpmn:startEvent id=\"subStart\">\n")
          .append("        <bpmn:outgoing>f3</bpmn:outgoing>\n")
          .append("      </bpmn:startEvent>\n");
        if (content == Content.USER) {
            // User task внутри MI-подпроцесса: assignee резолвится из inputElement
            // в скоупе инстанса (WO-IN-4) — отдельный класс семантики скоупов.
            sb.append("      <bpmn:sequenceFlow id=\"f3\" sourceRef=\"subStart\" targetRef=\"utProbe\" />\n");
            sb.append("      <bpmn:userTask id=\"utProbe\" name=\"utProbe\">\n")
              .append("        <bpmn:extensionElements>\n")
              .append("          <zeebe:userTask />\n")
              .append("          <zeebe:assignmentDefinition assignee=\"=" + INPUT_ELEMENT + "\" />\n")
              .append("        </bpmn:extensionElements>\n")
              .append("        <bpmn:incoming>f3</bpmn:incoming>\n")
              .append("        <bpmn:outgoing>f5</bpmn:outgoing>\n")
              .append("      </bpmn:userTask>\n");
            sb.append("      <bpmn:sequenceFlow id=\"f5\" sourceRef=\"utProbe\" targetRef=\"subEnd\" />\n");
            sb.append("      <bpmn:endEvent id=\"subEnd\">\n")
              .append("        <bpmn:incoming>f5</bpmn:incoming>\n")
              .append("      </bpmn:endEvent>\n");
            sb.append("    </bpmn:subProcess>\n");
            return sb.toString();
        }
        sb.append("      <bpmn:sequenceFlow id=\"f3\" sourceRef=\"subStart\" targetRef=\"work\" />\n");
        sb.append("      <bpmn:serviceTask id=\"work\" name=\"work\">\n")
          .append("        <bpmn:extensionElements>\n")
          .append("          <zeebe:taskDefinition type=\"" + WORK_JOB_TYPE + "\" />\n")
          .append("        </bpmn:extensionElements>\n")
          .append("        <bpmn:incoming>f3</bpmn:incoming>\n");
        if (content == Content.CHAIN) {
            sb.append("        <bpmn:outgoing>f4</bpmn:outgoing>\n")
              .append("      </bpmn:serviceTask>\n")
              .append("      <bpmn:sequenceFlow id=\"f4\" sourceRef=\"work\" targetRef=\"work2\" />\n")
              .append("      <bpmn:serviceTask id=\"work2\" name=\"work2\">\n")
              .append("        <bpmn:extensionElements>\n")
              .append("          <zeebe:taskDefinition type=\"" + WORK2_JOB_TYPE + "\" />\n")
              .append("        </bpmn:extensionElements>\n")
              .append("        <bpmn:incoming>f4</bpmn:incoming>\n")
              .append("        <bpmn:outgoing>f5</bpmn:outgoing>\n")
              .append("      </bpmn:serviceTask>\n")
              .append("      <bpmn:sequenceFlow id=\"f5\" sourceRef=\"work2\" targetRef=\"subEnd\" />\n");
        } else {
            sb.append("        <bpmn:outgoing>f5</bpmn:outgoing>\n")
              .append("      </bpmn:serviceTask>\n")
              .append("      <bpmn:sequenceFlow id=\"f5\" sourceRef=\"work\" targetRef=\"subEnd\" />\n");
        }
        sb.append("      <bpmn:endEvent id=\"subEnd\">\n")
          .append("        <bpmn:incoming>f5</bpmn:incoming>\n")
          .append("      </bpmn:endEvent>\n");
        sb.append("    </bpmn:subProcess>\n");
        return sb.toString();
    }

    /** {@code zeebe:loopCharacteristics} — общий для MI-задачи и MI-подпроцесса. */
    private static String loopCharacteristics(Container container) {
        String indent = container.onSubProcess() ? "      " : "      ";
        return indent + "<bpmn:multiInstanceLoopCharacteristics isSequential=\""
                + (!container.parallel()) + "\">\n"
                + indent + "  <bpmn:extensionElements>\n"
                + indent + "    <zeebe:loopCharacteristics inputCollection=\"=" + COLLECTION_VAR + "\""
                + " inputElement=\"" + INPUT_ELEMENT + "\""
                + " outputCollection=\"" + OUTPUT_COLLECTION + "\""
                + " outputElement=\"=" + INPUT_ELEMENT + "\" />\n"
                + indent + "  </bpmn:extensionElements>\n"
                + indent + "</bpmn:multiInstanceLoopCharacteristics>\n";
    }

    /** Уникальные оси сценариев (для сводки покрытия в отчёте). */
    public static Set<String> axesOf(List<BpmnScenario> scenarios) {
        Set<String> axes = new LinkedHashSet<>();
        for (BpmnScenario s : scenarios) {
            axes.add(s.container() + "/" + s.content() + "/n" + s.cardinality());
        }
        return axes;
    }

    // =====================================================================
    // СРЕЗ F2 — «ПОТОКИ» (WO-OPS-28, ФАЗА 2 кампании)
    // =====================================================================

    /**
     * Ось «потоки» — ФАЗА 2 кампании (WO-OPS-28, §«Фазы»: exclusive/parallel/
     * inclusive/event-based gateway, условные и дефолтные потоки, циклы возврата,
     * join-семантика).
     *
     * <p>Сценарии F2 — парные: одна и та же BPMN-схема прогоняется на нашем
     * движке и на ЖИВОМ Zeebe 8.5.0 (оракул), с ОДНИМ И ТЕМ ЖЕ списком действий
     * ({@link ScenarioAction}). Наблюдение — то же: «наблюдательная» задача
     * {@code dump} в конце, её payload = переменные корневого скоупа.
     */
    public enum Flow {
        /** Exclusive gateway: одно условие истинно — пошла его ветвь. */
        EXCLUSIVE_CONDITION,
        /** Exclusive gateway: ни одно условие не истинно — пошла ветвь default. */
        EXCLUSIVE_DEFAULT,
        /** Exclusive gateway: ни одно условие не истинно, default'а нет — инцидент. */
        EXCLUSIVE_NO_MATCH,
        /** Exclusive gateway: истинны ОБА условия — какой поток выбран (tie-break). */
        EXCLUSIVE_MULTI_MATCH,
        /** Parallel gateway: форк в две ветви, обе завершены — джойн проходит. */
        PARALLEL_FORK_JOIN,
        /** Parallel gateway: завершена только одна ветвь — джойн ЖДЁТ вторую. */
        PARALLEL_JOIN_INCOMPLETE,
        /** Inclusive gateway: истинны оба условия — обе ветви, джойн проходит. */
        INCLUSIVE_ALL,
        /** Inclusive gateway: истинно одно условие — джойн НЕ ждёт не взятую ветвь. */
        INCLUSIVE_PARTIAL,
        /**
         * Inclusive gateway БЕЗ джойна (только форк): обе ветви истинны.
         *
         * <p>Отдельный сценарий, потому что Zeebe 8.5 вообще НЕ УМЕЕТ джойн
         * inclusive-шлюза: живой деплой отвергает модель с двумя входящими
         * потоками («Currently the inclusive gateway can only have one incoming
         * sequence flow»). Форк без джойна Zeebe 8.5 принимает — значит семантику
         * ФОРКА можно сравнить с живым оракулом, а джойн — только как факт
         * ограничения версии.
         */
        INCLUSIVE_SPLIT_ALL,
        /** Inclusive gateway без джойна: истинно одно условие — идёт одна ветвь. */
        INCLUSIVE_SPLIT_ONE,
        /**
         * Inclusive gateway с ветвью default: ни одно условие не истинно — идёт
         * default. Отдельный сценарий, потому что default-ветвь у inclusive — это
         * отдельная семантика (у exclusive она проверена сценарием
         * {@code f2-exclusive-default}).
         */
        INCLUSIVE_DEFAULT,
        /** Event-based gateway: сработало одно из двух событий — второе отзывается. */
        EVENT_BASED_MESSAGE,
        /**
         * Event-based gateway: после срабатывания ПЕРВОГО события второе
         * публикуется немедленно. В Zeebe проигравшая подписка снимается в момент
         * срабатывания выигравшей — второе сообщение не должно ничего запускать.
         */
        EVENT_BASED_MESSAGE_RACE,
        /** Цикл возврата: exclusive gateway возвращает токен назад, потом выход. */
        LOOP_BACK
    }

    /** Префикс оси «потоки» в id сценария. */
    public static final String FLOW_CONTAINER = "F2";
    /** Тип job'а «наблюдательной» задачи (общий с F1). */
    public static final String FLOW_DUMP_JOB_TYPE = DUMP_JOB_TYPE;
    /** Стартовые переменные сценариев с сообщениями (ключи корреляции). */
    public static final String EVENT_VARS = "{\"keyA\":\"KA\",\"keyB\":\"KB\"}";

    /**
     * Полный срез F2 (ФАЗА 2 кампании).
     *
     * <p>Детерминированный, как и срез F1: ни случайности, ни внешних
     * зависимостей — один и тот же набор схем при каждом запуске, иначе
     * дифференциальный прогон против оракула сравнивал бы разное.
     */
    public static List<BpmnScenario> generateFlows() {
        List<BpmnScenario> out = new ArrayList<>();
        out.add(exclusiveCondition("f2-exclusive-condition-a", true));
        out.add(exclusiveCondition("f2-exclusive-condition-b", false));
        out.add(exclusiveDefault());
        out.add(exclusiveNoMatch());
        out.add(exclusiveMultiMatch("f2-exclusive-multi-match-ab", true));
        out.add(exclusiveMultiMatch("f2-exclusive-multi-match-ba", false));
        out.add(parallelForkJoin("f2-parallel-fork-join", true));
        out.add(parallelForkJoin("f2-parallel-join-incomplete", false));
        out.add(inclusiveJoin("f2-inclusive-all", true, true));
        out.add(inclusiveJoin("f2-inclusive-partial", true, false));
        out.add(inclusiveSplit("f2-inclusive-split-all", true, true));
        out.add(inclusiveSplit("f2-inclusive-split-one", true, false));
        out.add(inclusiveDefault());
        out.add(eventBasedMessage());
        out.add(eventBasedMessageRace());
        out.add(loopBack());
        return out;
    }

    /** Exclusive gateway: единственное истинное условие выбирает ветвь. */
    private static BpmnScenario exclusiveCondition(String id, boolean flagA) {
        StringBuilder sb = new StringBuilder();
        sb.append(defsHeader(""));
        sb.append("  <bpmn:process id=\"ops28f2\" name=\"ops28f2\" isExecutable=\"true\">\n");
        sb.append("    <bpmn:startEvent id=\"start\">\n      <bpmn:outgoing>f1</bpmn:outgoing>\n    </bpmn:startEvent>\n");
        sb.append("    <bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"gw\" />\n");
        sb.append("    <bpmn:exclusiveGateway id=\"gw\">\n");
        sb.append("      <bpmn:incoming>f1</bpmn:incoming>\n");
        sb.append("      <bpmn:outgoing>fA</bpmn:outgoing>\n");
        sb.append("      <bpmn:outgoing>fB</bpmn:outgoing>\n");
        sb.append("    </bpmn:exclusiveGateway>\n");
        sb.append(conditionalFlow("fA", "gw", "taskA", "=flagA"));
        sb.append(conditionalFlow("fB", "gw", "taskB", "=flagB"));
        sb.append(serviceTask("taskA", "branchA", "fA", "fA2"));
        sb.append(serviceTask("taskB", "branchB", "fB", "fB2"));
        sb.append("    <bpmn:sequenceFlow id=\"fA2\" sourceRef=\"taskA\" targetRef=\"mergeGw\" />\n");
        sb.append("    <bpmn:sequenceFlow id=\"fB2\" sourceRef=\"taskB\" targetRef=\"mergeGw\" />\n");
        sb.append(mergeGateway("mergeGw", List.of("fA2", "fB2"), "fMerge"));
        sb.append("    <bpmn:sequenceFlow id=\"fMerge\" sourceRef=\"mergeGw\" targetRef=\"dump\" />\n");
        sb.append(dumpTail("fMerge"));
        sb.append("  </bpmn:process>\n</bpmn:definitions>\n");

        List<ScenarioAction> actions = List.of(
                new ScenarioAction(ScenarioAction.COMPLETE, "taskA", "{\"bA\":\"A\"}"),
                new ScenarioAction(ScenarioAction.COMPLETE, "taskB", "{\"bB\":\"B\"}"));
        return flowScenario(id, Flow.EXCLUSIVE_CONDITION, sb.toString(),
                "{\"flagA\":" + flagA + ",\"flagB\":" + (!flagA) + "}",
                List.of("taskA", "taskB"), List.of("branchA", "branchB"), actions);
    }

    /** Exclusive gateway: ни одно условие не истинно — ветвь default. */
    private static BpmnScenario exclusiveDefault() {
        StringBuilder sb = new StringBuilder();
        sb.append(defsHeader(""));
        sb.append("  <bpmn:process id=\"ops28f2\" name=\"ops28f2\" isExecutable=\"true\">\n");
        sb.append("    <bpmn:startEvent id=\"start\">\n      <bpmn:outgoing>f1</bpmn:outgoing>\n    </bpmn:startEvent>\n");
        sb.append("    <bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"gw\" />\n");
        sb.append("    <bpmn:exclusiveGateway id=\"gw\" default=\"fD\">\n");
        sb.append("      <bpmn:incoming>f1</bpmn:incoming>\n");
        sb.append("      <bpmn:outgoing>fA</bpmn:outgoing>\n");
        sb.append("      <bpmn:outgoing>fB</bpmn:outgoing>\n");
        sb.append("      <bpmn:outgoing>fD</bpmn:outgoing>\n");
        sb.append("    </bpmn:exclusiveGateway>\n");
        sb.append(conditionalFlow("fA", "gw", "taskA", "=flagA"));
        sb.append(conditionalFlow("fB", "gw", "taskB", "=flagB"));
        sb.append("    <bpmn:sequenceFlow id=\"fD\" sourceRef=\"gw\" targetRef=\"taskD\" />\n");
        sb.append(serviceTask("taskA", "branchA", "fA", "fA2"));
        sb.append(serviceTask("taskB", "branchB", "fB", "fB2"));
        sb.append(serviceTask("taskD", "branchD", "fD", "fD2"));
        sb.append("    <bpmn:sequenceFlow id=\"fA2\" sourceRef=\"taskA\" targetRef=\"mergeGw\" />\n");
        sb.append("    <bpmn:sequenceFlow id=\"fB2\" sourceRef=\"taskB\" targetRef=\"mergeGw\" />\n");
        sb.append("    <bpmn:sequenceFlow id=\"fD2\" sourceRef=\"taskD\" targetRef=\"mergeGw\" />\n");
        sb.append(mergeGateway("mergeGw", List.of("fA2", "fB2", "fD2"), "fMerge"));
        sb.append("    <bpmn:sequenceFlow id=\"fMerge\" sourceRef=\"mergeGw\" targetRef=\"dump\" />\n");
        sb.append(dumpTail("fMerge"));
        sb.append("  </bpmn:process>\n</bpmn:definitions>\n");

        List<ScenarioAction> actions = List.of(
                new ScenarioAction(ScenarioAction.COMPLETE, "taskA", "{\"bA\":\"A\"}"),
                new ScenarioAction(ScenarioAction.COMPLETE, "taskB", "{\"bB\":\"B\"}"),
                new ScenarioAction(ScenarioAction.COMPLETE, "taskD", "{\"bD\":\"D\"}"));
        return flowScenario("f2-exclusive-default", Flow.EXCLUSIVE_DEFAULT, sb.toString(),
                "{\"flagA\":false,\"flagB\":false}",
                List.of("taskA", "taskB", "taskD"), List.of("branchA", "branchB", "branchD"), actions);
    }

    /** Exclusive gateway без default: ни одно условие не истинно — инцидент. */
    private static BpmnScenario exclusiveNoMatch() {
        StringBuilder sb = new StringBuilder();
        sb.append(defsHeader(""));
        sb.append("  <bpmn:process id=\"ops28f2\" name=\"ops28f2\" isExecutable=\"true\">\n");
        sb.append("    <bpmn:startEvent id=\"start\">\n      <bpmn:outgoing>f1</bpmn:outgoing>\n    </bpmn:startEvent>\n");
        sb.append("    <bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"gw\" />\n");
        sb.append("    <bpmn:exclusiveGateway id=\"gw\">\n");
        sb.append("      <bpmn:incoming>f1</bpmn:incoming>\n");
        sb.append("      <bpmn:outgoing>fA</bpmn:outgoing>\n");
        sb.append("      <bpmn:outgoing>fB</bpmn:outgoing>\n");
        sb.append("    </bpmn:exclusiveGateway>\n");
        sb.append(conditionalFlow("fA", "gw", "taskA", "=flagA"));
        sb.append(conditionalFlow("fB", "gw", "taskB", "=flagB"));
        sb.append(serviceTask("taskA", "branchA", "fA", "fA2"));
        sb.append(serviceTask("taskB", "branchB", "fB", "fB2"));
        sb.append("    <bpmn:sequenceFlow id=\"fA2\" sourceRef=\"taskA\" targetRef=\"mergeGw\" />\n");
        sb.append("    <bpmn:sequenceFlow id=\"fB2\" sourceRef=\"taskB\" targetRef=\"mergeGw\" />\n");
        sb.append(mergeGateway("mergeGw", List.of("fA2", "fB2"), "fMerge"));
        sb.append("    <bpmn:sequenceFlow id=\"fMerge\" sourceRef=\"mergeGw\" targetRef=\"dump\" />\n");
        sb.append(dumpTail("fMerge"));
        sb.append("  </bpmn:process>\n</bpmn:definitions>\n");

        List<ScenarioAction> actions = List.of(
                new ScenarioAction(ScenarioAction.COMPLETE, "taskA", "{\"bA\":\"A\"}"),
                new ScenarioAction(ScenarioAction.COMPLETE, "taskB", "{\"bB\":\"B\"}"));
        return flowScenario("f2-exclusive-no-match", Flow.EXCLUSIVE_NO_MATCH, sb.toString(),
                "{\"flagA\":false,\"flagB\":false}",
                List.of("taskA", "taskB"), List.of("branchA", "branchB"), actions);
    }

    /**
     * Exclusive gateway с ДВУМЯ одновременно истинными условиями: какой поток
     * выбран? Вариант {@code aFirst} — условие A объявлено первым, {@code false} —
     * первым объявлено B (порядок объявления в документе разный, условия те же).
     */
    private static BpmnScenario exclusiveMultiMatch(String id, boolean aFirst) {
        String first = aFirst ? "A" : "B";
        String second = aFirst ? "B" : "A";
        StringBuilder sb = new StringBuilder();
        sb.append(defsHeader(""));
        sb.append("  <bpmn:process id=\"ops28f2\" name=\"ops28f2\" isExecutable=\"true\">\n");
        sb.append("    <bpmn:startEvent id=\"start\">\n      <bpmn:outgoing>f1</bpmn:outgoing>\n    </bpmn:startEvent>\n");
        sb.append("    <bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"gw\" />\n");
        sb.append("    <bpmn:exclusiveGateway id=\"gw\">\n");
        sb.append("      <bpmn:incoming>f1</bpmn:incoming>\n");
        sb.append("      <bpmn:outgoing>f").append(first).append("</bpmn:outgoing>\n");
        sb.append("      <bpmn:outgoing>f").append(second).append("</bpmn:outgoing>\n");
        sb.append("    </bpmn:exclusiveGateway>\n");
        sb.append(conditionalFlow("f" + first, "gw", "task" + first, "=flag" + first));
        sb.append(conditionalFlow("f" + second, "gw", "task" + second, "=flag" + second));
        sb.append(serviceTask("taskA", "branchA", "fA", "fA2"));
        sb.append(serviceTask("taskB", "branchB", "fB", "fB2"));
        sb.append("    <bpmn:sequenceFlow id=\"fA2\" sourceRef=\"taskA\" targetRef=\"mergeGw\" />\n");
        sb.append("    <bpmn:sequenceFlow id=\"fB2\" sourceRef=\"taskB\" targetRef=\"mergeGw\" />\n");
        sb.append(mergeGateway("mergeGw", List.of("fA2", "fB2"), "fMerge"));
        sb.append("    <bpmn:sequenceFlow id=\"fMerge\" sourceRef=\"mergeGw\" targetRef=\"dump\" />\n");
        sb.append(dumpTail("fMerge"));
        sb.append("  </bpmn:process>\n</bpmn:definitions>\n");

        List<ScenarioAction> actions = List.of(
                new ScenarioAction(ScenarioAction.COMPLETE, "taskA", "{\"bA\":\"A\"}"),
                new ScenarioAction(ScenarioAction.COMPLETE, "taskB", "{\"bB\":\"B\"}"));
        return flowScenario(id, Flow.EXCLUSIVE_MULTI_MATCH, sb.toString(),
                "{\"flagA\":true,\"flagB\":true}",
                List.of("taskA", "taskB"), List.of("branchA", "branchB"), actions);
    }

    /**
     * Parallel gateway: форк в две ветви. {@code completeBoth=true} — обе ветви
     * завершены (джойн проходит); {@code false} — завершена только одна (джойн
     * ждёт вторую ветвь — процесс не доходит до наблюдательной задачи).
     */
    private static BpmnScenario parallelForkJoin(String id, boolean completeBoth) {
        StringBuilder sb = new StringBuilder();
        sb.append(defsHeader(""));
        sb.append("  <bpmn:process id=\"ops28f2\" name=\"ops28f2\" isExecutable=\"true\">\n");
        sb.append("    <bpmn:startEvent id=\"start\">\n      <bpmn:outgoing>f1</bpmn:outgoing>\n    </bpmn:startEvent>\n");
        sb.append("    <bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"fork\" />\n");
        sb.append("    <bpmn:parallelGateway id=\"fork\">\n");
        sb.append("      <bpmn:incoming>f1</bpmn:incoming>\n");
        sb.append("      <bpmn:outgoing>fP1</bpmn:outgoing>\n");
        sb.append("      <bpmn:outgoing>fP2</bpmn:outgoing>\n");
        sb.append("    </bpmn:parallelGateway>\n");
        sb.append("    <bpmn:sequenceFlow id=\"fP1\" sourceRef=\"fork\" targetRef=\"taskP1\" />\n");
        sb.append("    <bpmn:sequenceFlow id=\"fP2\" sourceRef=\"fork\" targetRef=\"taskP2\" />\n");
        sb.append(serviceTask("taskP1", "branchP1", "fP1", "fP1b"));
        sb.append(serviceTask("taskP2", "branchP2", "fP2", "fP2b"));
        sb.append("    <bpmn:sequenceFlow id=\"fP1b\" sourceRef=\"taskP1\" targetRef=\"join\" />\n");
        sb.append("    <bpmn:sequenceFlow id=\"fP2b\" sourceRef=\"taskP2\" targetRef=\"join\" />\n");
        sb.append("    <bpmn:parallelGateway id=\"join\">\n");
        sb.append("      <bpmn:incoming>fP1b</bpmn:incoming>\n");
        sb.append("      <bpmn:incoming>fP2b</bpmn:incoming>\n");
        sb.append("      <bpmn:outgoing>fJoinOut</bpmn:outgoing>\n");
        sb.append("    </bpmn:parallelGateway>\n");
        sb.append("    <bpmn:sequenceFlow id=\"fJoinOut\" sourceRef=\"join\" targetRef=\"dump\" />\n");
        sb.append(dumpTail("fJoinOut"));
        sb.append("  </bpmn:process>\n</bpmn:definitions>\n");

        List<ScenarioAction> actions = new ArrayList<>();
        actions.add(new ScenarioAction(ScenarioAction.COMPLETE, "taskP1", "{\"bP1\":\"P1\"}"));
        if (completeBoth) {
            actions.add(new ScenarioAction(ScenarioAction.COMPLETE, "taskP2", "{\"bP2\":\"P2\"}"));
        }
        return flowScenario(id, completeBoth ? Flow.PARALLEL_FORK_JOIN : Flow.PARALLEL_JOIN_INCOMPLETE,
                sb.toString(), "{}",
                List.of("taskP1", "taskP2"), List.of("branchP1", "branchP2"), actions);
    }

    /**
     * Inclusive gateway: форк по двум условиям, джойн. Оба истинны — обе ветви
     * идут (джойн ждёт обе); одно истинно — идёт одна, и джойн НЕ должен ждать
     * вторую (не взятую) ветвь.
     */
    private static BpmnScenario inclusiveJoin(String id, boolean flagA, boolean flagB) {
        StringBuilder sb = new StringBuilder();
        sb.append(defsHeader(""));
        sb.append("  <bpmn:process id=\"ops28f2\" name=\"ops28f2\" isExecutable=\"true\">\n");
        sb.append("    <bpmn:startEvent id=\"start\">\n      <bpmn:outgoing>f1</bpmn:outgoing>\n    </bpmn:startEvent>\n");
        sb.append("    <bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"ifork\" />\n");
        sb.append("    <bpmn:inclusiveGateway id=\"ifork\">\n");
        sb.append("      <bpmn:incoming>f1</bpmn:incoming>\n");
        sb.append("      <bpmn:outgoing>fI1</bpmn:outgoing>\n");
        sb.append("      <bpmn:outgoing>fI2</bpmn:outgoing>\n");
        sb.append("    </bpmn:inclusiveGateway>\n");
        sb.append(conditionalFlow("fI1", "ifork", "taskI1", "=flagA"));
        sb.append(conditionalFlow("fI2", "ifork", "taskI2", "=flagB"));
        sb.append(serviceTask("taskI1", "branchI1", "fI1", "fI1b"));
        sb.append(serviceTask("taskI2", "branchI2", "fI2", "fI2b"));
        sb.append("    <bpmn:sequenceFlow id=\"fI1b\" sourceRef=\"taskI1\" targetRef=\"ijoin\" />\n");
        sb.append("    <bpmn:sequenceFlow id=\"fI2b\" sourceRef=\"taskI2\" targetRef=\"ijoin\" />\n");
        sb.append("    <bpmn:inclusiveGateway id=\"ijoin\">\n");
        sb.append("      <bpmn:incoming>fI1b</bpmn:incoming>\n");
        sb.append("      <bpmn:incoming>fI2b</bpmn:incoming>\n");
        sb.append("      <bpmn:outgoing>fIJoinOut</bpmn:outgoing>\n");
        sb.append("    </bpmn:inclusiveGateway>\n");
        sb.append("    <bpmn:sequenceFlow id=\"fIJoinOut\" sourceRef=\"ijoin\" targetRef=\"dump\" />\n");
        sb.append(dumpTail("fIJoinOut"));
        sb.append("  </bpmn:process>\n</bpmn:definitions>\n");

        List<ScenarioAction> actions = List.of(
                new ScenarioAction(ScenarioAction.COMPLETE, "taskI1", "{\"bI1\":\"I1\"}"),
                new ScenarioAction(ScenarioAction.COMPLETE, "taskI2", "{\"bI2\":\"I2\"}"));
        return flowScenario(id, flagB ? Flow.INCLUSIVE_ALL : Flow.INCLUSIVE_PARTIAL, sb.toString(),
                "{\"flagA\":" + flagA + ",\"flagB\":" + flagB + "}",
                List.of("taskI1", "taskI2"), List.of("branchI1", "branchI2"), actions);
    }

    /**
     * Inclusive gateway БЕЗ джойна: форк по двум условиям, каждая ветвь уходит в
     * свой конец (одна — через наблюдательную задачу).
     *
     * <p>Зачем отдельная схема: в Zeebe 8.5 джойн inclusive-шлюза (несколько
     * входящих потоков) НЕ ПОДДЕРЖИВАЕТСЯ — живой деплой отвергает модель. Форк
     * (один входящий, два исходящих) Zeebe 8.5 принимает, поэтому именно его
     * семантику можно сравнить с живым оракулом.
     *
     * <p>Порядок действий: сначала завершается ВТОРАЯ ветвь, потом первая — так
     * переменные обеих ветвей гарантированно попадают в payload наблюдательной
     * задачи (в Zeebe payload job'а = переменные скоупа на момент активации).
     */
    private static BpmnScenario inclusiveSplit(String id, boolean flagA, boolean flagB) {
        StringBuilder sb = new StringBuilder();
        sb.append(defsHeader(""));
        sb.append("  <bpmn:process id=\"ops28f2\" name=\"ops28f2\" isExecutable=\"true\">\n");
        sb.append("    <bpmn:startEvent id=\"start\">\n      <bpmn:outgoing>f1</bpmn:outgoing>\n    </bpmn:startEvent>\n");
        sb.append("    <bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"ifork\" />\n");
        sb.append("    <bpmn:inclusiveGateway id=\"ifork\">\n");
        sb.append("      <bpmn:incoming>f1</bpmn:incoming>\n");
        sb.append("      <bpmn:outgoing>fI1</bpmn:outgoing>\n");
        sb.append("      <bpmn:outgoing>fI2</bpmn:outgoing>\n");
        sb.append("    </bpmn:inclusiveGateway>\n");
        sb.append(conditionalFlow("fI1", "ifork", "taskI1", "=flagA"));
        sb.append(conditionalFlow("fI2", "ifork", "taskI2", "=flagB"));
        sb.append(serviceTask("taskI1", "branchI1", "fI1", "fI1b"));
        sb.append(serviceTask("taskI2", "branchI2", "fI2", "fI2b"));
        sb.append("    <bpmn:sequenceFlow id=\"fI1b\" sourceRef=\"taskI1\" targetRef=\"dump\" />\n");
        sb.append("    <bpmn:sequenceFlow id=\"fI2b\" sourceRef=\"taskI2\" targetRef=\"end2\" />\n");
        sb.append("    <bpmn:endEvent id=\"end2\">\n      <bpmn:incoming>fI2b</bpmn:incoming>\n    </bpmn:endEvent>\n");
        sb.append(dumpTail("fI1b"));
        sb.append("  </bpmn:process>\n</bpmn:definitions>\n");

        List<ScenarioAction> actions = List.of(
                new ScenarioAction(ScenarioAction.COMPLETE, "taskI2", "{\"bI2\":\"I2\"}"),
                new ScenarioAction(ScenarioAction.COMPLETE, "taskI1", "{\"bI1\":\"I1\"}"));
        return flowScenario(id, flagB ? Flow.INCLUSIVE_SPLIT_ALL : Flow.INCLUSIVE_SPLIT_ONE, sb.toString(),
                "{\"flagA\":" + flagA + ",\"flagB\":" + flagB + "}",
                List.of("taskI1", "taskI2"), List.of("branchI1", "branchI2"), actions);
    }


    /**
     * Inclusive gateway с ветвью default: оба условия ложны → идёт default.
     *
     * <p>Ветви расходятся по трём task'ам, каждая пишет СВОЮ переменную, и все
     * сходятся в exclusive-merge перед наблюдательной задачей — так по payload
     * видно, КАКАЯ ветвь взята (одна переменная из трёх).
     */
    private static BpmnScenario inclusiveDefault() {
        StringBuilder sb = new StringBuilder();
        sb.append(defsHeader(""));
        sb.append("  <bpmn:process id=\"ops28f2\" name=\"ops28f2\" isExecutable=\"true\">\n");
        sb.append("    <bpmn:startEvent id=\"start\">\n      <bpmn:outgoing>f1</bpmn:outgoing>\n    </bpmn:startEvent>\n");
        sb.append("    <bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"ifork\" />\n");
        sb.append("    <bpmn:inclusiveGateway id=\"ifork\" default=\"fD\">\n");
        sb.append("      <bpmn:incoming>f1</bpmn:incoming>\n");
        sb.append("      <bpmn:outgoing>fI1</bpmn:outgoing>\n");
        sb.append("      <bpmn:outgoing>fI2</bpmn:outgoing>\n");
        sb.append("      <bpmn:outgoing>fD</bpmn:outgoing>\n");
        sb.append("    </bpmn:inclusiveGateway>\n");
        sb.append(conditionalFlow("fI1", "ifork", "taskI1", "=flagA"));
        sb.append(conditionalFlow("fI2", "ifork", "taskI2", "=flagB"));
        sb.append("    <bpmn:sequenceFlow id=\"fD\" sourceRef=\"ifork\" targetRef=\"taskD\" />\n");
        sb.append(serviceTask("taskI1", "branchI1", "fI1", "fI1b"));
        sb.append(serviceTask("taskI2", "branchI2", "fI2", "fI2b"));
        sb.append(serviceTask("taskD", "branchD", "fD", "fDb"));
        sb.append("    <bpmn:sequenceFlow id=\"fI1b\" sourceRef=\"taskI1\" targetRef=\"mergeGw\" />\n");
        sb.append("    <bpmn:sequenceFlow id=\"fI2b\" sourceRef=\"taskI2\" targetRef=\"mergeGw\" />\n");
        sb.append("    <bpmn:sequenceFlow id=\"fDb\" sourceRef=\"taskD\" targetRef=\"mergeGw\" />\n");
        sb.append(mergeGateway("mergeGw", List.of("fI1b", "fI2b", "fDb"), "fMerge"));
        sb.append("    <bpmn:sequenceFlow id=\"fMerge\" sourceRef=\"mergeGw\" targetRef=\"dump\" />\n");
        sb.append(dumpTail("fMerge"));
        sb.append("  </bpmn:process>\n</bpmn:definitions>\n");

        List<ScenarioAction> actions = List.of(
                new ScenarioAction(ScenarioAction.COMPLETE, "taskI1", "{\"bI1\":\"I1\"}"),
                new ScenarioAction(ScenarioAction.COMPLETE, "taskI2", "{\"bI2\":\"I2\"}"),
                new ScenarioAction(ScenarioAction.COMPLETE, "taskD", "{\"bD\":\"D\"}"));
        return flowScenario("f2-inclusive-default", Flow.INCLUSIVE_DEFAULT, sb.toString(),
                "{\"flagA\":false,\"flagB\":false}",
                List.of("taskI1", "taskI2", "taskD"), List.of("branchI1", "branchI2", "branchD"), actions);
    }

    /**
     * Event-based gateway: два сообщения-кандидата, публикуется ОДНО. Ожидание:
     * сработавшая ветвь идёт, а подписка второго события отзывается (второй
     * обработчик НЕ выполняется).
     */
    private static BpmnScenario eventBasedMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append(defsHeader(messageDefinitions()));
        sb.append("  <bpmn:process id=\"ops28f2\" name=\"ops28f2\" isExecutable=\"true\">\n");
        sb.append("    <bpmn:startEvent id=\"start\">\n      <bpmn:outgoing>f1</bpmn:outgoing>\n    </bpmn:startEvent>\n");
        sb.append("    <bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"ebg\" />\n");
        sb.append("    <bpmn:eventBasedGateway id=\"ebg\">\n");
        sb.append("      <bpmn:incoming>f1</bpmn:incoming>\n");
        sb.append("      <bpmn:outgoing>fM</bpmn:outgoing>\n");
        sb.append("      <bpmn:outgoing>fT</bpmn:outgoing>\n");
        sb.append("    </bpmn:eventBasedGateway>\n");
        sb.append("    <bpmn:sequenceFlow id=\"fM\" sourceRef=\"ebg\" targetRef=\"catchA\" />\n");
        sb.append("    <bpmn:intermediateCatchEvent id=\"catchA\">\n");
        sb.append("      <bpmn:incoming>fM</bpmn:incoming>\n");
        sb.append("      <bpmn:outgoing>fA2</bpmn:outgoing>\n");
        sb.append("      <bpmn:messageEventDefinition id=\"medA\" messageRef=\"msgA\" />\n");
        sb.append("    </bpmn:intermediateCatchEvent>\n");
        sb.append("    <bpmn:sequenceFlow id=\"fT\" sourceRef=\"ebg\" targetRef=\"catchB\" />\n");
        sb.append("    <bpmn:intermediateCatchEvent id=\"catchB\">\n");
        sb.append("      <bpmn:incoming>fT</bpmn:incoming>\n");
        sb.append("      <bpmn:outgoing>fB2</bpmn:outgoing>\n");
        sb.append("      <bpmn:messageEventDefinition id=\"medB\" messageRef=\"msgB\" />\n");
        sb.append("    </bpmn:intermediateCatchEvent>\n");
        sb.append("    <bpmn:sequenceFlow id=\"fA2\" sourceRef=\"catchA\" targetRef=\"taskA\" />\n");
        sb.append("    <bpmn:sequenceFlow id=\"fB2\" sourceRef=\"catchB\" targetRef=\"taskB\" />\n");
        sb.append(serviceTask("taskA", "branchA", "fA2", "fA3"));
        sb.append(serviceTask("taskB", "branchB", "fB2", "fB3"));
        sb.append("    <bpmn:sequenceFlow id=\"fA3\" sourceRef=\"taskA\" targetRef=\"mergeGw\" />\n");
        sb.append("    <bpmn:sequenceFlow id=\"fB3\" sourceRef=\"taskB\" targetRef=\"mergeGw\" />\n");
        sb.append(mergeGateway("mergeGw", List.of("fA3", "fB3"), "fMerge"));
        sb.append("    <bpmn:sequenceFlow id=\"fMerge\" sourceRef=\"mergeGw\" targetRef=\"dump\" />\n");
        sb.append(dumpTail("fMerge"));
        sb.append("  </bpmn:process>\n</bpmn:definitions>\n");

        List<ScenarioAction> actions = List.of(
                new ScenarioAction(ScenarioAction.PUBLISH, "msgA", "{\"msg\":\"A\"}", "KA"),
                new ScenarioAction(ScenarioAction.COMPLETE, "taskA", "{\"bA\":\"A\"}"),
                new ScenarioAction(ScenarioAction.COMPLETE, "taskB", "{\"bB\":\"B\"}"));
        return flowScenario("f2-event-based-message", Flow.EVENT_BASED_MESSAGE, sb.toString(),
                EVENT_VARS,
                List.of("taskA", "taskB"), List.of("branchA", "branchB"), actions);
    }

    /**
     * Event-based gateway: выигравшее событие сработало, и СРАЗУ публикуется
     * проигравшее.
     *
     * <p>Ожидание Zeebe 8.5: проигравшая подписка снята в момент срабатывания
     * выигравшей, поэтому второе сообщение никому не достаётся и вторая ветвь НЕ
     * начинается. Если движок держит проигравшую подписку живой до конца
     * инстанса — второе сообщение её разбудит (и вторая ветвь пойдёт).
     */
    private static BpmnScenario eventBasedMessageRace() {
        StringBuilder sb = new StringBuilder();
        sb.append(defsHeader(messageDefinitions()));
        sb.append("  <bpmn:process id=\"ops28f2\" name=\"ops28f2\" isExecutable=\"true\">\n");
        sb.append("    <bpmn:startEvent id=\"start\">\n      <bpmn:outgoing>f1</bpmn:outgoing>\n    </bpmn:startEvent>\n");
        sb.append("    <bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"ebg\" />\n");
        sb.append("    <bpmn:eventBasedGateway id=\"ebg\">\n");
        sb.append("      <bpmn:incoming>f1</bpmn:incoming>\n");
        sb.append("      <bpmn:outgoing>fM</bpmn:outgoing>\n");
        sb.append("      <bpmn:outgoing>fT</bpmn:outgoing>\n");
        sb.append("    </bpmn:eventBasedGateway>\n");
        sb.append("    <bpmn:sequenceFlow id=\"fM\" sourceRef=\"ebg\" targetRef=\"catchA\" />\n");
        sb.append("    <bpmn:intermediateCatchEvent id=\"catchA\">\n");
        sb.append("      <bpmn:incoming>fM</bpmn:incoming>\n");
        sb.append("      <bpmn:outgoing>fA2</bpmn:outgoing>\n");
        sb.append("      <bpmn:messageEventDefinition id=\"medA\" messageRef=\"msgA\" />\n");
        sb.append("    </bpmn:intermediateCatchEvent>\n");
        sb.append("    <bpmn:sequenceFlow id=\"fT\" sourceRef=\"ebg\" targetRef=\"catchB\" />\n");
        sb.append("    <bpmn:intermediateCatchEvent id=\"catchB\">\n");
        sb.append("      <bpmn:incoming>fT</bpmn:incoming>\n");
        sb.append("      <bpmn:outgoing>fB2</bpmn:outgoing>\n");
        sb.append("      <bpmn:messageEventDefinition id=\"medB\" messageRef=\"msgB\" />\n");
        sb.append("    </bpmn:intermediateCatchEvent>\n");
        sb.append("    <bpmn:sequenceFlow id=\"fA2\" sourceRef=\"catchA\" targetRef=\"taskA\" />\n");
        sb.append("    <bpmn:sequenceFlow id=\"fB2\" sourceRef=\"catchB\" targetRef=\"taskB\" />\n");
        sb.append(serviceTask("taskA", "branchA", "fA2", "fA3"));
        sb.append(serviceTask("taskB", "branchB", "fB2", "fB3"));
        sb.append("    <bpmn:sequenceFlow id=\"fA3\" sourceRef=\"taskA\" targetRef=\"mergeGw\" />\n");
        sb.append("    <bpmn:sequenceFlow id=\"fB3\" sourceRef=\"taskB\" targetRef=\"mergeGw\" />\n");
        sb.append(mergeGateway("mergeGw", List.of("fA3", "fB3"), "fMerge"));
        sb.append("    <bpmn:sequenceFlow id=\"fMerge\" sourceRef=\"mergeGw\" targetRef=\"dump\" />\n");
        sb.append(dumpTail("fMerge"));
        sb.append("  </bpmn:process>\n</bpmn:definitions>\n");

        List<ScenarioAction> actions = List.of(
                new ScenarioAction(ScenarioAction.PUBLISH, "msgA", "{\"msg\":\"A\"}", "KA"),
                new ScenarioAction(ScenarioAction.PUBLISH, "msgB", "{\"msg\":\"B\"}", "KB"),
                new ScenarioAction(ScenarioAction.COMPLETE, "taskA", "{\"bA\":\"A\"}"),
                new ScenarioAction(ScenarioAction.COMPLETE, "taskB", "{\"bB\":\"B\"}"));
        return flowScenario("f2-event-based-message-race", Flow.EVENT_BASED_MESSAGE_RACE, sb.toString(),
                EVENT_VARS,
                List.of("taskA", "taskB"), List.of("branchA", "branchB"), actions);
    }

    /**
     * Цикл возврата: задача → exclusive gateway → ОБРАТНО на задачу (условие
     * {@code =again}), затем — выход по default. Два прохода: первый возвращает
     * токен назад, второй уходит к наблюдательной задаче.
     */
    private static BpmnScenario loopBack() {
        StringBuilder sb = new StringBuilder();
        sb.append(defsHeader(""));
        sb.append("  <bpmn:process id=\"ops28f2\" name=\"ops28f2\" isExecutable=\"true\">\n");
        sb.append("    <bpmn:startEvent id=\"start\">\n      <bpmn:outgoing>f1</bpmn:outgoing>\n    </bpmn:startEvent>\n");
        sb.append("    <bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"taskL1\" />\n");
        sb.append(serviceTask("taskL1", "loopTask", "f1", "fL2"));
        sb.append("    <bpmn:sequenceFlow id=\"fL2\" sourceRef=\"taskL1\" targetRef=\"lgw\" />\n");
        sb.append("    <bpmn:exclusiveGateway id=\"lgw\" default=\"fExit\">\n");
        sb.append("      <bpmn:incoming>fL2</bpmn:incoming>\n");
        sb.append("      <bpmn:outgoing>fLoop</bpmn:outgoing>\n");
        sb.append("      <bpmn:outgoing>fExit</bpmn:outgoing>\n");
        sb.append("    </bpmn:exclusiveGateway>\n");
        sb.append(conditionalFlow("fLoop", "lgw", "taskL1", "=again"));
        sb.append("    <bpmn:sequenceFlow id=\"fExit\" sourceRef=\"lgw\" targetRef=\"dump\" />\n");
        sb.append(dumpTail("fExit"));
        sb.append("  </bpmn:process>\n</bpmn:definitions>\n");

        // Три прохода: два возврата по кругу и выход. Три, а не два, — чтобы
        // цикл не прошёл «случайно» (однократный возврат может быть и
        // особенностью реализации, а не циклом).
        List<ScenarioAction> actions = List.of(
                new ScenarioAction(ScenarioAction.COMPLETE, "taskL1", "{\"again\":true,\"iter\":1}"),
                new ScenarioAction(ScenarioAction.COMPLETE, "taskL1", "{\"again\":true,\"iter\":2}"),
                new ScenarioAction(ScenarioAction.COMPLETE, "taskL1", "{\"again\":false,\"iter\":3}"));
        return flowScenario("f2-loop-back", Flow.LOOP_BACK, sb.toString(), "{}",
                List.of("taskL1"), List.of("loopTask"), actions);
    }


    /**
     * Определения сообщений в диалекте Zeebe 8.5.
     *
     * <p>Zeebe 8.5 ОТВЕРГАет модель без {@code zeebe:subscription} и требует
     * непустой {@code correlationKey} (проба живым деплоем:
     * {@code docs/analysis/c8-oracle/probe-message-dialect.js}):
     * «Attribute 'correlationKey' must be present and not empty»). Поэтому у
     * каждого сообщения СВОЙ ключ корреляции ({@code keyA}/{@code keyB}) — иначе
     * публикация одного сообщения разбудила бы обе подписки (fan-out), а
     * event-based gateway требует «первое событие выиграло, второе отозвано».
     */
    private static String messageDefinitions() {
        return "  <bpmn:message id=\"msgA\" name=\"msgA\">\n"
                + "    <bpmn:extensionElements>\n"
                + "      <zeebe:subscription correlationKey=\"=keyA\" />\n"
                + "    </bpmn:extensionElements>\n"
                + "  </bpmn:message>\n"
                + "  <bpmn:message id=\"msgB\" name=\"msgB\">\n"
                + "    <bpmn:extensionElements>\n"
                + "      <zeebe:subscription correlationKey=\"=keyB\" />\n"
                + "    </bpmn:extensionElements>\n"
                + "  </bpmn:message>\n";
    }

    /** Заголовок definitions (с xsi — для conditionExpression). */
    private static String defsHeader(String messages) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
                + " xmlns:zeebe=\"http://camunda.org/schema/zeebe/1.0\""
                + " id=\"defs_ops28f2\" targetNamespace=\"http://bpmn.io/schema/bpmn\">\n"
                + messages;
    }

    /** Последовательный поток с условием FEEL. */
    private static String conditionalFlow(String id, String source, String target, String condition) {
        return "    <bpmn:sequenceFlow id=\"" + id + "\" sourceRef=\"" + source + "\" targetRef=\"" + target + "\">\n"
                + "      <bpmn:conditionExpression xsi:type=\"bpmn:tFormalExpression\">" + condition
                + "</bpmn:conditionExpression>\n"
                + "    </bpmn:sequenceFlow>\n";
    }

    /** Service task с zeebe:taskDefinition (job-воркер). */
    private static String serviceTask(String id, String jobType, String incoming, String outgoing) {
        return "    <bpmn:serviceTask id=\"" + id + "\" name=\"" + id + "\">\n"
                + "      <bpmn:extensionElements>\n"
                + "        <zeebe:taskDefinition type=\"" + jobType + "\" />\n"
                + "      </bpmn:extensionElements>\n"
                + "      <bpmn:incoming>" + incoming + "</bpmn:incoming>\n"
                + "      <bpmn:outgoing>" + outgoing + "</bpmn:outgoing>\n"
                + "    </bpmn:serviceTask>\n";
    }

    /**
     * Exclusive MERGE-шлюз (несколько входящих, один исходящий) — «слив» ветви
     * перед наблюдательной задачей.
     *
     * <p>Зачем он вместо нескольких входящих потоков прямо в задачу: задача с
     * двумя входящими — это НЕЯВНЫЙ join, а его семантика в Zeebe 8.5 (создаётся
     * по экземпляру на каждый входящий токен) отличается от нашей. Явный
     * exclusive-merge (XORслив: каждый пришедший токен проходит насквозь) —
     * единственная форма, которую оба движка понимают ОДИНАКОВО, и она не
     * добавляет к проверяемой семантике ничего своего (ветви тут взаимоисключающие).
     */
    private static String mergeGateway(String id, List<String> incoming, String outgoing) {
        StringBuilder sb = new StringBuilder();
        sb.append("    <bpmn:exclusiveGateway id=\"").append(id).append("\">\n");
        for (String in : incoming) {
            sb.append("      <bpmn:incoming>").append(in).append("</bpmn:incoming>\n");
        }
        sb.append("      <bpmn:outgoing>").append(outgoing).append("</bpmn:outgoing>\n");
        sb.append("    </bpmn:exclusiveGateway>\n");
        return sb.toString();
    }

    /** Хвост «наблюдательная задача → конец» (одинаков для всех схем среза F2). */
    private static String dumpTail(String incomingFlowId) {
        return "    <bpmn:serviceTask id=\"dump\" name=\"dump\">\n"
                + "      <bpmn:extensionElements>\n"
                + "        <zeebe:taskDefinition type=\"" + FLOW_DUMP_JOB_TYPE + "\" />\n"
                + "      </bpmn:extensionElements>\n"
                + "      <bpmn:incoming>" + incomingFlowId + "</bpmn:incoming>\n"
                + "      <bpmn:outgoing>fDumpOut</bpmn:outgoing>\n"
                + "    </bpmn:serviceTask>\n"
                + "    <bpmn:sequenceFlow id=\"fDumpOut\" sourceRef=\"dump\" targetRef=\"end\" />\n"
                + "    <bpmn:endEvent id=\"end\">\n      <bpmn:incoming>fDumpOut</bpmn:incoming>\n    </bpmn:endEvent>\n";
    }

    /** Сборка сценария среза F2. */
    private static BpmnScenario flowScenario(String id, Flow flow, String bpmn, String varsJson,
            List<String> taskElementIds, List<String> jobTypes, List<ScenarioAction> actions) {
        return new BpmnScenario(id, FLOW_CONTAINER, flow.name(), 0, List.of(), List.of(), bpmn, varsJson,
                taskElementIds, "dump", 1, List.of(), jobTypes, FLOW_DUMP_JOB_TYPE, actions);
    }

    // ─── Срез F5 («сквозные», WO-OPS-28 ФАЗА 5) ────────────────────────────

    /**
     * Ось «сквозные» (ФАЗА 5 кампании): рестарт движка посреди процесса,
     * параллельные завершения (гонки {@code lockInstanceFirst}), миграция версии
     * определения для живых инстансов.
     */
    public enum Cross {
        /** Рестарт движка посреди процесса (durable state). */
        RESTART_MIDPROCESS,
        /** Параллельные завершения двух ветвей (гонка за instance-lock). */
        PARALLEL_RACE,
        /** Миграция версии определения v1→v2 при живом инстансе. */
        VERSION_MIGRATION
    }

    /** Префикс оси «сквозные» в id сценария. */
    public static final String CROSS_CONTAINER = "F5";
    /** Тип job'а рабочей задачи среза F5 (тот же, что у F1/F2). */
    public static final String CROSS_JOB_TYPE = WORK_JOB_TYPE;
    /** Ключ (id процесса) пары версий для оси «миграция версии». */
    public static final String VERSIONED_PROCESS_KEY = "ops28f5versioned";

    /**
     * Полный срез F5 (ФАЗА 5 кампании).
     *
     * <p>Детерминированный, как срезы F1/F2. Схемы — в том же Zeebe-совместимом
     * диалекте, чтобы дифференциальный прогон против живого Zeebe 8.5.0
     * сравнивал ПОВЕДЕНИЕ, а не разные модели.
     *
     * <p>Пара версий ({@code f5-version-v1} / {@code f5-version-v2}) — это ОДИН
     * и тот же ключ процесса ({@link #VERSIONED_PROCESS_KEY}) с РАЗНЫМ
     * продолжением: v1 идёт в {@code stV1}, v2 — в {@code stV2}. Оба деплоятся
     * одним ключом, поэтому у инстанса, стартовавшего на v1, после деплоя v2
     * проверяется, КАКОЙ версией он продолжится (в Zeebe 8.5 — своей: версия
     * фиксируется на старте).
     */
    public static List<BpmnScenario> generateCrossCutting() {
        return List.of(
                restartMidProcess(),
                parallelRace(),
                versionScenario("f5-version-v1", "stV1"),
                versionScenario("f5-version-v2", "stV2"));
    }

    /**
     * Рестарт движка посреди процесса: старт → задача {@code stMid} (висит) →
     * задача {@code stAfter} → наблюдательная.
     *
     * <p>Именно «висит на середине» — ради этого сценарий и нужен: рестарт
     * происходит, пока живая задача ждёт воркера. Продолжить процесс должен
     * ТОЛЬКО движок, поднятый ПОСЛЕ рестарта (второй Spring-контекст на той же
     * БД) — если бы состояние жило в памяти первого движка, продолжить было бы
     * нечем.
     */
    private static BpmnScenario restartMidProcess() {
        StringBuilder sb = new StringBuilder();
        sb.append(defsHeader(""));
        sb.append("  <bpmn:process id=\"ops28f5\" name=\"ops28f5\" isExecutable=\"true\">\n");
        sb.append("    <bpmn:startEvent id=\"start\">\n      <bpmn:outgoing>f1</bpmn:outgoing>\n    </bpmn:startEvent>\n");
        sb.append("    <bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"stMid\" />\n");
        sb.append(serviceTask("stMid", CROSS_JOB_TYPE, "f1", "f2"));
        sb.append("    <bpmn:sequenceFlow id=\"f2\" sourceRef=\"stMid\" targetRef=\"stAfter\" />\n");
        sb.append(serviceTask("stAfter", CROSS_JOB_TYPE, "f2", "f3"));
        sb.append("    <bpmn:sequenceFlow id=\"f3\" sourceRef=\"stAfter\" targetRef=\"dump\" />\n");
        sb.append(dumpTail("f3"));
        sb.append("  </bpmn:process>\n</bpmn:definitions>\n");

        List<ScenarioAction> actions = List.of(
                new ScenarioAction(ScenarioAction.COMPLETE, "stMid", "{\"phase\":\"mid\"}"),
                new ScenarioAction(ScenarioAction.COMPLETE, "stAfter", "{\"phase\":\"after\"}"));
        return crossScenario("f5-restart-midprocess", Cross.RESTART_MIDPROCESS, sb.toString(),
                "{\"started\":\"yes\"}", List.of("stMid", "stAfter"), List.of(CROSS_JOB_TYPE), actions);
    }

    /**
     * Параллельные завершения: форк в ЧЕТЫРЕ ветви, все задачи завершаются
     * ОДНОВРЕМЕННО (четыре реальных потока), джойн после них — наблюдательная.
     *
     * <p>Почему ветвей четыре, а не две: счётчик незавершённых ветвей форка — ОДНА
     * строка, и все четыре потока спорят за неё. С двумя ветвями окно lost-update
     * (прочитать счётчик → записать) слишком узкое, чтобы поймать гонку
     * УБЕДИТЕЛЬНО; с четырьмя конкурентами на одной строке мутация «снять
     * row-lock» материализуется устойчиво (проверено: см. отчёт фазы, POF).
     */
    private static BpmnScenario parallelRace() {
        StringBuilder sb = new StringBuilder();
        sb.append(defsHeader(""));
        sb.append("  <bpmn:process id=\"ops28f5\" name=\"ops28f5\" isExecutable=\"true\">\n");
        sb.append("    <bpmn:startEvent id=\"start\">\n      <bpmn:outgoing>f1</bpmn:outgoing>\n    </bpmn:startEvent>\n");
        sb.append("    <bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"fork\" />\n");
        List<String> branchIds = List.of("stA", "stB", "stC", "stD");
        sb.append("    <bpmn:parallelGateway id=\"fork\">\n");
        sb.append("      <bpmn:incoming>f1</bpmn:incoming>\n");
        for (String branch : branchIds) {
            sb.append("      <bpmn:outgoing>f").append(branch).append("</bpmn:outgoing>\n");
        }
        sb.append("    </bpmn:parallelGateway>\n");
        for (String branch : branchIds) {
            sb.append("    <bpmn:sequenceFlow id=\"f").append(branch).append("\" sourceRef=\"fork\" targetRef=\"")
              .append(branch).append("\" />\n");
            sb.append(serviceTask(branch, CROSS_JOB_TYPE, "f" + branch, "f" + branch + "2"));
            sb.append("    <bpmn:sequenceFlow id=\"f").append(branch).append("2\" sourceRef=\"").append(branch)
              .append("\" targetRef=\"join\" />\n");
        }
        sb.append("    <bpmn:parallelGateway id=\"join\">\n");
        for (String branch : branchIds) {
            sb.append("      <bpmn:incoming>f").append(branch).append("2</bpmn:incoming>\n");
        }
        sb.append("      <bpmn:outgoing>fJoinOut</bpmn:outgoing>\n");
        sb.append("    </bpmn:parallelGateway>\n");
        sb.append("    <bpmn:sequenceFlow id=\"fJoinOut\" sourceRef=\"join\" targetRef=\"dump\" />\n");
        sb.append(dumpTail("fJoinOut"));
        sb.append("  </bpmn:process>\n</bpmn:definitions>\n");

        List<ScenarioAction> actions = new ArrayList<>();
        for (String branch : branchIds) {
            actions.add(new ScenarioAction(ScenarioAction.COMPLETE, branch, "{\"" + branch + "\":\"" + branch + "\"}"));
        }
        return crossScenario("f5-parallel-race", Cross.PARALLEL_RACE, sb.toString(),
                "{}", branchIds, List.of(CROSS_JOB_TYPE), actions);
    }

    /**
     * Версия определения для оси «миграция версии»: ключ ОДИН И ТОТ ЖЕ
     * ({@link #VERSIONED_PROCESS_KEY}), продолжение разное ({@code stV1} или
     * {@code stV2}).
     */
    private static BpmnScenario versionScenario(String id, String versionTaskId) {
        StringBuilder sb = new StringBuilder();
        sb.append(defsHeader(""));
        sb.append("  <bpmn:process id=\"").append(VERSIONED_PROCESS_KEY).append("\" name=\"")
          .append(VERSIONED_PROCESS_KEY).append("\" isExecutable=\"true\">\n");
        sb.append("    <bpmn:startEvent id=\"start\">\n      <bpmn:outgoing>f1</bpmn:outgoing>\n    </bpmn:startEvent>\n");
        sb.append("    <bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"stA\" />\n");
        sb.append(serviceTask("stA", CROSS_JOB_TYPE, "f1", "f2"));
        sb.append("    <bpmn:sequenceFlow id=\"f2\" sourceRef=\"stA\" targetRef=\"").append(versionTaskId).append("\" />\n");
        sb.append(serviceTask(versionTaskId, CROSS_JOB_TYPE, "f2", "f3"));
        sb.append("    <bpmn:sequenceFlow id=\"f3\" sourceRef=\"").append(versionTaskId).append("\" targetRef=\"dump\" />\n");
        sb.append(dumpTail("f3"));
        sb.append("  </bpmn:process>\n</bpmn:definitions>\n");

        List<ScenarioAction> actions = List.of(
                new ScenarioAction(ScenarioAction.COMPLETE, "stA", "{\"phase\":\"a\"}"),
                new ScenarioAction(ScenarioAction.COMPLETE, versionTaskId, "{\"phase\":\"" + versionTaskId + "\"}"));
        return crossScenario(id, Cross.VERSION_MIGRATION, sb.toString(),
                "{}", List.of("stA", versionTaskId), List.of(CROSS_JOB_TYPE), actions);
    }

    /** Сборка сценария среза F5. */
    private static BpmnScenario crossScenario(String id, Cross cross, String bpmn, String varsJson,
            List<String> taskElementIds, List<String> jobTypes, List<ScenarioAction> actions) {
        return new BpmnScenario(id, CROSS_CONTAINER, cross.name(), 0, List.of(), List.of(), bpmn, varsJson,
                taskElementIds, "dump", 1, List.of(), jobTypes, FLOW_DUMP_JOB_TYPE, actions);
    }

    /**
     * ПАРНАЯ схема версии для оси «миграция версии» (нужна оракулу: деплой второй
     * версии ТОГО ЖЕ ключа, пока инстанс версии 1 ещё жив).
     *
     * <p>Возвращает {@code null}, если у сценария нет пары (у остальных осей парной
     * версии нет — там «вторая версия» не имеет смысла).
     */
    public static String pairedVersionBpmn(String scenarioId) {
        if ("f5-version-v1".equals(scenarioId)) {
            return versionScenario("f5-version-v2", "stV2").bpmn();
        }
        if ("f5-version-v2".equals(scenarioId)) {
            return versionScenario("f5-version-v1", "stV1").bpmn();
        }
        return null;
    }

    /**
     * Завершать задачи сценария ОДНОВРЕМЕННО (для оракула: параллельные завершения
     * нескольких job'ов одним залпом — так проверяется, что брокер не «прострелит»
     * джойн дважды).
     *
     * <p>На нашем движке то же самое делают четыре РЕАЛЬНЫХ потока с барьером
     * (см. {@code CrossCuttingScenarioSupport.parallelRace}).
     */
    public static boolean parallelActions(BpmnScenario scenario) {
        return Cross.PARALLEL_RACE.name().equals(scenario.content());
    }
}
