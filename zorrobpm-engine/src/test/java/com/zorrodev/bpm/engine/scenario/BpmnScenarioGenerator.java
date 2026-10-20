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
    /** Тип job'а user task в Zeebe 8.5. */
    public static final String USER_TASK_JOB_TYPE = "io.camunda:user-task";

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
            // Zeebe 8.5: user task — job типа io.camunda:user-task.
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
}
