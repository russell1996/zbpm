package com.zorrodev.bpm.engine.scenario;

import java.util.ArrayList;
import java.util.List;

/**
 * WO-OPS-28 ФАЗА 3: генератор среза «контейнеры» (F3).
 *
 * <p>Расширение генератора WO-OPS-27 ({@link BpmnScenarioGenerator}) — тот же
 * Zeebe-совместимый диалект, те же id, тот же «наблюдательный» job {@code dump}
 * в конце, те же типизированные переменные. Отличие — в САМОМ КОНТЕЙНЕРЕ:
 * подпроцесс (обычный/вложенный/MI), event sub-process, call activity,
 * transaction, completionCondition.
 *
 * <p>Каждый сценарий — ОДИН и тот же XML для обеих сторон (наш движок и живой
 * Zeebe 8.5.0): иначе «дифференциальный прогон» сравнивал бы разные модели,
 * а не поведение. Диалект выбран по проверенным на живом Zeebe 8.5 фикстурам
 * C8-41 (docs/analysis/c8-oracle/bpmn/*, zorrobpm-engine/src/test/files/test-c841-*):
 * {@code zeebe:loopCharacteristics} внутри {@code multiInstanceLoopCharacteristics},
 * {@code completionCondition} — ДОчерним элементом (не атрибутом; атрибутная
 * форма Zeebe молча игнорирует), {@code incoming/outgoing} контейнера — ПЕРЕД
 * {@code multiInstanceLoopCharacteristics} (иначе XSD-валидатор Zeebe отвергает
 * схему: «One of laneSet, flowElement, artifact is expected»).
 *
 * <p>Оси среза F3:
 * <ul>
 *   <li><b>контейнер</b>: обычный subProcess, вложенный subProcess, MI-subProcess
 *       (parallel/sequential), event sub-process (interrupting/non-interrupting),
 *       call activity, transaction;</li>
 *   <li><b>свойство контейнера</b>: completionCondition (MI на задаче и MI на
 *       подпроцессе), MI на call activity (отказ при деплое у нас);</li>
 *   <li><b>область видимости</b>: переменная, записанная ВНУТРИ контейнера, —
 *       доходит ли она до корневого скоупа (в Zeebe запись completion'а идёт в
 *       СОДЕРЖАЩИЙ скоуп, то есть в скоуп контейнера, а не в корень).</li>
 * </ul>
 */
public final class ContainerScenarioGenerator {

    /** Префикс оси «контейнеры» в id сценария. */
    public static final String CONTAINER_AXIS = "F3";

    /** Тип job'а рабочей задачи внутри контейнера. */
    public static final String WORK = "work";
    /** Тип job'а дочернего процесса (call activity). */
    public static final String CHILD_WORK = "childwork";
    /** Тип job'а тела event sub-process. */
    public static final String EV_WORK = "evwork";
    /** Тип job'а задачи ПОСЛЕ event sub-process (main flow). */
    public static final String AFTER = "after";
    /** Тип job'а задачи ПОСЛЕ отменённой транзакции. */
    public static final String AFTER_CANCEL = "aftercancel";
    /** Тип job'а «наблюдательной» задачи. */
    public static final String DUMP = "dump";

    /** Ключ дочернего процесса для call activity. */
    public static final String CHILD_PROCESS_KEY = "f3child";

    private ContainerScenarioGenerator() {
    }

    /** Полный срез F3 (детерминированный, как и срезы F1/F2). */
    public static List<ContainerScenario> generate() {
        List<ContainerScenario> out = new ArrayList<>();
        out.add(plainSubProcess());
        out.add(plainSubProcessWithVars());
        out.add(nestedSubProcess());
        out.add(nestedSubProcessWithVars());
        out.add(miSubProcess(false));
        out.add(miSubProcess(true));
        out.add(miSubProcessCompletionCondition());
        out.add(miTaskCompletionCondition());
        out.add(eventSubProcess(true));
        out.add(eventSubProcess(false));
        out.add(callActivity(false));
        out.add(callActivity(true));
        out.add(miCallActivity());
        out.add(transaction(false));
        out.add(transaction(true));
        return out;
    }

    // =====================================================================
    // Обычный (не MI) подпроцесс
    // =====================================================================

    /** Обычный встроенный подпроцесс: одна service task внутри, контейнер закрывается. */
    private static ContainerScenario plainSubProcess() {
        String bpmn = process("f3-subprocess-plain",
                "  <bpmn:subProcess id=\"sub\" name=\"sub\">\n"
                        + "    <bpmn:incoming>f1</bpmn:incoming>\n"
                        + "    <bpmn:outgoing>f2</bpmn:outgoing>\n"
                        + "    <bpmn:startEvent id=\"subStart\"><bpmn:outgoing>sf1</bpmn:outgoing></bpmn:startEvent>\n"
                        + "    <bpmn:sequenceFlow id=\"sf1\" sourceRef=\"subStart\" targetRef=\"work\" />\n"
                        + serviceTask("work", type("f3-subprocess-plain", WORK), "sf1", "sf2")
                        + "    <bpmn:sequenceFlow id=\"sf2\" sourceRef=\"work\" targetRef=\"subEnd\" />\n"
                        + "    <bpmn:endEvent id=\"subEnd\"><bpmn:incoming>sf2</bpmn:incoming></bpmn:endEvent>\n"
                        + "  </bpmn:subProcess>\n",
                "sub", "sub", null);
        return scenario("f3-subprocess-plain", "SUBPROCESS_PLAIN", bpmn, List.of(),
                List.of(new ContainerScenario.Step("work", type("f3-subprocess-plain", WORK))), List.of(),
                "Обычный встроенный подпроцеск: контейнер закрывается, поток идёт дальше, "
                        + "утечек нет.");
    }

    /** То же, но работа пишет переменную: проверяем область видимости (скоуп контейнера). */
    private static ContainerScenario plainSubProcessWithVars() {
        String bpmn = process("f3-subprocess-plain-vars",
                "  <bpmn:subProcess id=\"sub\" name=\"sub\">\n"
                        + "    <bpmn:incoming>f1</bpmn:incoming>\n"
                        + "    <bpmn:outgoing>f2</bpmn:outgoing>\n"
                        + "    <bpmn:startEvent id=\"subStart\"><bpmn:outgoing>sf1</bpmn:outgoing></bpmn:startEvent>\n"
                        + "    <bpmn:sequenceFlow id=\"sf1\" sourceRef=\"subStart\" targetRef=\"work\" />\n"
                        + serviceTask("work", type("f3-subprocess-plain-vars", WORK), "sf1", "sf2")
                        + "    <bpmn:sequenceFlow id=\"sf2\" sourceRef=\"work\" targetRef=\"subEnd\" />\n"
                        + "    <bpmn:endEvent id=\"subEnd\"><bpmn:incoming>sf2</bpmn:incoming></bpmn:endEvent>\n"
                        + "  </bpmn:subProcess>\n",
                "sub", "sub", null);
        return scenario("f3-subprocess-plain-vars", "SUBPROCESS_PLAIN_VARS", bpmn, List.of(),
                List.of(new ContainerScenario.Step("work", type("f3-subprocess-plain-vars", WORK), "{\"inner\":\"IV\"}")), List.of(),
                "Переменная, записанная ВНУТРИ подпроцесса: доходит ли она до корневого скоупа "
                        + "(в Zeebe completion-переменные пишутся в СОДЕРЖАЩИЙ скоуп).");
    }

    /** Вложенный подпроцесс (подпроцесс в подпроцессе). */
    private static ContainerScenario nestedSubProcess() {
        String bpmn = process("f3-subprocess-nested",
                "  <bpmn:subProcess id=\"outer\" name=\"outer\">\n"
                        + "    <bpmn:incoming>f1</bpmn:incoming>\n"
                        + "    <bpmn:outgoing>f2</bpmn:outgoing>\n"
                        + "    <bpmn:startEvent id=\"outerStart\"><bpmn:outgoing>of1</bpmn:outgoing></bpmn:startEvent>\n"
                        + "    <bpmn:sequenceFlow id=\"of1\" sourceRef=\"outerStart\" targetRef=\"inner\" />\n"
                        + "    <bpmn:subProcess id=\"inner\" name=\"inner\">\n"
                        + "      <bpmn:incoming>of1</bpmn:incoming>\n"
                        + "      <bpmn:outgoing>of2</bpmn:outgoing>\n"
                        + "      <bpmn:startEvent id=\"innerStart\"><bpmn:outgoing>if1</bpmn:outgoing></bpmn:startEvent>\n"
                        + "      <bpmn:sequenceFlow id=\"if1\" sourceRef=\"innerStart\" targetRef=\"work\" />\n"
                        + serviceTask("work", type("f3-subprocess-nested", WORK), "if1", "if2")
                        + "      <bpmn:sequenceFlow id=\"if2\" sourceRef=\"work\" targetRef=\"innerEnd\" />\n"
                        + "      <bpmn:endEvent id=\"innerEnd\"><bpmn:incoming>if2</bpmn:incoming></bpmn:endEvent>\n"
                        + "    </bpmn:subProcess>\n"
                        + "    <bpmn:sequenceFlow id=\"of2\" sourceRef=\"inner\" targetRef=\"outerEnd\" />\n"
                        + "    <bpmn:endEvent id=\"outerEnd\"><bpmn:incoming>of2</bpmn:incoming></bpmn:endEvent>\n"
                        + "  </bpmn:subProcess>\n",
                "outer", "outer", null);
        return scenario("f3-subprocess-nested", "SUBPROCESS_NESTED", bpmn, List.of(),
                List.of(new ContainerScenario.Step("work", type("f3-subprocess-nested", WORK))), List.of(),
                "Вложенный подпроцесс: оба контейнера закрываются, поток идёт дальше.");
    }

    /** Вложенный подпроцесс + переменная из ВНУТРЕННЕГО контейнера. */
    private static ContainerScenario nestedSubProcessWithVars() {
        String bpmn = process("f3-subprocess-nested-vars",
                "  <bpmn:subProcess id=\"outer\" name=\"outer\">\n"
                        + "    <bpmn:incoming>f1</bpmn:incoming>\n"
                        + "    <bpmn:outgoing>f2</bpmn:outgoing>\n"
                        + "    <bpmn:startEvent id=\"outerStart\"><bpmn:outgoing>of1</bpmn:outgoing></bpmn:startEvent>\n"
                        + "    <bpmn:sequenceFlow id=\"of1\" sourceRef=\"outerStart\" targetRef=\"inner\" />\n"
                        + "    <bpmn:subProcess id=\"inner\" name=\"inner\">\n"
                        + "      <bpmn:incoming>of1</bpmn:incoming>\n"
                        + "      <bpmn:outgoing>of2</bpmn:outgoing>\n"
                        + "      <bpmn:startEvent id=\"innerStart\"><bpmn:outgoing>if1</bpmn:outgoing></bpmn:startEvent>\n"
                        + "      <bpmn:sequenceFlow id=\"if1\" sourceRef=\"innerStart\" targetRef=\"work\" />\n"
                        + serviceTask("work", type("f3-subprocess-nested-vars", WORK), "if1", "if2")
                        + "      <bpmn:sequenceFlow id=\"if2\" sourceRef=\"work\" targetRef=\"innerEnd\" />\n"
                        + "      <bpmn:endEvent id=\"innerEnd\"><bpmn:incoming>if2</bpmn:incoming></bpmn:endEvent>\n"
                        + "    </bpmn:subProcess>\n"
                        + "    <bpmn:sequenceFlow id=\"of2\" sourceRef=\"inner\" targetRef=\"outerEnd\" />\n"
                        + "    <bpmn:endEvent id=\"outerEnd\"><bpmn:incoming>of2</bpmn:incoming></bpmn:endEvent>\n"
                        + "  </bpmn:subProcess>\n",
                "outer", "outer", null);
        return scenario("f3-subprocess-nested-vars", "SUBPROCESS_NESTED_VARS", bpmn, List.of(),
                List.of(new ContainerScenario.Step("work", type("f3-subprocess-nested-vars", WORK), "{\"inner\":\"IV\"}")), List.of(),
                "Переменная из ВНУТРЕННЕГО контейнера: видна ли она в корне (и в промежуточном скоупе)?");
    }

    // =====================================================================
    // MI-подпроцесс
    // =====================================================================

    /** MI-подпроцесс (parallel или sequential) на двух инстансах. */
    private static ContainerScenario miSubProcess(boolean sequential) {
        String id = sequential ? "f3-mi-subprocess-sequential" : "f3-mi-subprocess-parallel";
        String bpmn = process(id,
                "  <bpmn:subProcess id=\"miSub\" name=\"miSub\">\n"
                        + "    <bpmn:incoming>f1</bpmn:incoming>\n"
                        + "    <bpmn:outgoing>f2</bpmn:outgoing>\n"
                        + loopCharacteristics(sequential, false)
                        + "    <bpmn:startEvent id=\"subStart\"><bpmn:outgoing>sf1</bpmn:outgoing></bpmn:startEvent>\n"
                        + "    <bpmn:sequenceFlow id=\"sf1\" sourceRef=\"subStart\" targetRef=\"work\" />\n"
                        + serviceTask("work", type(id, WORK), "sf1", "sf2")
                        + "    <bpmn:sequenceFlow id=\"sf2\" sourceRef=\"work\" targetRef=\"subEnd\" />\n"
                        + "    <bpmn:endEvent id=\"subEnd\"><bpmn:incoming>sf2</bpmn:incoming></bpmn:endEvent>\n"
                        + "  </bpmn:subProcess>\n",
                "miSub", "miSub", null);
        return scenario(id, sequential ? "MI_SUBPROCESS_SEQUENTIAL" : "MI_SUBPROCESS_PARALLEL", bpmn,
                List.of(),
                List.of(new ContainerScenario.Step("work", type(id, WORK), "{}", "a"),
                        new ContainerScenario.Step("work", type(id, WORK), "{}", "b")),
                List.of("a", "b"),
                "MI-подпроцесс (" + (sequential ? "sequential" : "parallel") + "): два инстанса, "
                        + "агрегация outputCollection в корне, утечек нет.");
    }

    /** MI-подпроцесс с completionCondition: завершаем ОДИН инстанс, остальные терминируются. */
    private static ContainerScenario miSubProcessCompletionCondition() {
        String bpmn = process("f3-mi-subprocess-completion-condition",
                "  <bpmn:subProcess id=\"miSub\" name=\"miSub\">\n"
                        + "    <bpmn:incoming>f1</bpmn:incoming>\n"
                        + "    <bpmn:outgoing>f2</bpmn:outgoing>\n"
                        + loopCharacteristics(false, true)
                        + "    <bpmn:startEvent id=\"subStart\"><bpmn:outgoing>sf1</bpmn:outgoing></bpmn:startEvent>\n"
                        + "    <bpmn:sequenceFlow id=\"sf1\" sourceRef=\"subStart\" targetRef=\"work\" />\n"
                        + serviceTask("work", type("f3-mi-subprocess-completion-condition", WORK), "sf1", "sf2")
                        + "    <bpmn:sequenceFlow id=\"sf2\" sourceRef=\"work\" targetRef=\"subEnd\" />\n"
                        + "    <bpmn:endEvent id=\"subEnd\"><bpmn:incoming>sf2</bpmn:incoming></bpmn:endEvent>\n"
                        + "  </bpmn:subProcess>\n",
                "miSub", "miSub", null);
        return scenario("f3-mi-subprocess-completion-condition", "MI_SUBPROCESS_COMPLETION_CONDITION",
                bpmn, List.of(),
                List.of(new ContainerScenario.Step("work", type("f3-mi-subprocess-completion-condition", WORK), "{}", "a")),
                List.of("a", "b", "c"),
                "MI-подпроцесс + completionCondition (=numberOfCompletedInstances >= 1): завершён "
                        + "ОДИН инстанс, остальные терминируются; частичная агрегация сохраняется.");
    }

    /** MI на ЗАДАЧЕ с completionCondition. */
    private static ContainerScenario miTaskCompletionCondition() {
        String bpmn = process("f3-mi-task-completion-condition",
                "  <bpmn:serviceTask id=\"miTask\" name=\"miTask\">\n"
                        + "    <bpmn:extensionElements>\n"
                        + "      <zeebe:taskDefinition type=\"" + type("f3-mi-task-completion-condition", WORK) + "\" />\n"
                        + "    </bpmn:extensionElements>\n"
                        // ПОРЯДОК ЭЛЕМЕНТОВ — как требует XSD BPMN 2.0 (Zeebe его валидирует):
                        // extensionElements, incoming, outgoing, и только потом loopCharacteristics.
                        // Наш парсер порядок не важен, Zeebe — отвергает схему (cvc-complex-type.2.4.d).
                        + "    <bpmn:incoming>f1</bpmn:incoming>\n"
                        + "    <bpmn:outgoing>f2</bpmn:outgoing>\n"
                        + loopCharacteristics(false, true)
                        + "  </bpmn:serviceTask>\n",
                "miTask", "miTask", null);
        return scenario("f3-mi-task-completion-condition", "MI_TASK_COMPLETION_CONDITION", bpmn,
                List.of(),
                List.of(new ContainerScenario.Step("miTask", type("f3-mi-task-completion-condition", WORK), "{}", "a")),
                List.of("a", "b", "c"),
                "MI на задаче + completionCondition: завершён ОДИН инстанс, сиблинги терминируются.");
    }

    // =====================================================================
    // Event sub-process
    // =====================================================================

    /**
     * Event sub-process, запускаемый СИГНАЛОМ из основного потока.
     *
     * <p>Триггер — сигнал, который бросает основной поток (intermediate throw
     * event): так не нужна публикация сообщения (отдельный механизм), а обе
     * стороны срабатывают по-одинаковому. Имя сигнала — УНИКАЛЬНОЕ на сценарий:
     * наш движок广播'ит сигнал по всем инстансам (1:N), и общий сигнал разбудил
     * бы чужой инстанс из другого сценария.
     */
    private static ContainerScenario eventSubProcess(boolean interrupting) {
        String id = interrupting ? "f3-event-subprocess-interrupting" : "f3-event-subprocess-non-interrupting";
        String signal = "f3sig-" + (interrupting ? "int" : "nonint");
        String bpmn = process(id,
                "  <bpmn:serviceTask id=\"work\" name=\"work\">\n"
                        + "    <bpmn:extensionElements>\n"
                        + "      <zeebe:taskDefinition type=\"" + type(id, WORK) + "\" />\n"
                        + "    </bpmn:extensionElements>\n"
                        + "    <bpmn:incoming>f1</bpmn:incoming>\n"
                        + "    <bpmn:outgoing>tf1</bpmn:outgoing>\n"
                        + "  </bpmn:serviceTask>\n"
                        + "  <bpmn:sequenceFlow id=\"tf1\" sourceRef=\"work\" targetRef=\"throwSig\" />\n"
                        + "  <bpmn:intermediateThrowEvent id=\"throwSig\">\n"
                        + "    <bpmn:incoming>tf1</bpmn:incoming>\n"
                        + "    <bpmn:outgoing>tf2</bpmn:outgoing>\n"
                        + "    <bpmn:signalEventDefinition id=\"sed1\" signalRef=\"" + signal + "\" />\n"
                        + "  </bpmn:intermediateThrowEvent>\n"
                        + "  <bpmn:sequenceFlow id=\"tf2\" sourceRef=\"throwSig\" targetRef=\"after\" />\n"
                        + serviceTask("after", type(id, AFTER), "tf2", "f2")
                        + "  <bpmn:subProcess id=\"esp\" name=\"esp\" triggeredByEvent=\"true\">\n"
                        + "    <bpmn:startEvent id=\"evStart\" isInterrupting=\"" + interrupting + "\">\n"
                        + "      <bpmn:outgoing>ef1</bpmn:outgoing>\n"
                        + "      <bpmn:signalEventDefinition id=\"sed2\" signalRef=\"" + signal + "\" />\n"
                        + "    </bpmn:startEvent>\n"
                        + "    <bpmn:sequenceFlow id=\"ef1\" sourceRef=\"evStart\" targetRef=\"evWork\" />\n"
                        + serviceTask("evWork", type(id, EV_WORK), "ef1", "ef2")
                        + "    <bpmn:sequenceFlow id=\"ef2\" sourceRef=\"evWork\" targetRef=\"evEnd\" />\n"
                        + "    <bpmn:endEvent id=\"evEnd\"><bpmn:incoming>ef2</bpmn:incoming></bpmn:endEvent>\n"
                        + "  </bpmn:subProcess>\n",
                "work", "after", null, signal);
        List<ContainerScenario.Step> steps = new ArrayList<>();
        steps.add(new ContainerScenario.Step("work", type(id, WORK)));
        steps.add(new ContainerScenario.Step("evWork", type(id, EV_WORK)));
        if (!interrupting) {
            steps.add(new ContainerScenario.Step("after", type(id, AFTER)));
        }
        return scenario(id, interrupting ? "EVENT_SUBPROCESS_INTERRUPTING" : "EVENT_SUBPROCESS_NON_INTERRUPTING",
                bpmn, List.of(), steps, List.of(),
                interrupting
                        ? "Event sub-process, ПРЕРЫВАЮЩИЙ: сигнал гасит основной поток — задача "
                                + "\"after\" не создаётся, наблюдательная задача недостижима, инстанс "
                                + "завершается по телу event sub-process."
                        : "Event sub-process, НЕПРЕРЫВАЮЩИЙ: тело event sub-process идёт ПАРАЛЛЕЛЬНО "
                                + "основному потоку, оба заканчиваются, наблюдательная задача достижима.");
    }

    // =====================================================================
    // Call activity
    // =====================================================================

    /** Call activity: дочерний процесс (отдельный деплой) → родитель продолжается. */
    private static ContainerScenario callActivity(boolean withVars) {
        String id = withVars ? "f3-call-activity-vars" : "f3-call-activity";
        String bpmn = process(id,
                "  <bpmn:callActivity id=\"call\" name=\"call\">\n"
                        + "    <bpmn:extensionElements>\n"
                        + "      <zeebe:calledElement processId=\"" + CHILD_PROCESS_KEY + "\" />\n"
                        + "    </bpmn:extensionElements>\n"
                        + "    <bpmn:incoming>f1</bpmn:incoming>\n"
                        + "    <bpmn:outgoing>f2</bpmn:outgoing>\n"
                        + "  </bpmn:callActivity>\n",
                "call", "call", null);
        return scenario(id, withVars ? "CALL_ACTIVITY_VARS" : "CALL_ACTIVITY", bpmn,
                List.of(childProcessBpmn(id)),
                List.of(new ContainerScenario.Step("childWork", type(id, CHILD_WORK),
                        withVars ? "{\"childVar\":\"CV\"}" : "{}")),
                List.of(),
                withVars
                        ? "Call activity: переменная дочернего процесса — доходит ли она в родителя "
                          + "(propagateAllChildVariables по умолчанию)."
                        : "Call activity: дочерний процесс отрабатывает, родитель продолжается, "
                          + "утечек нет.");
    }

    /** MI на call activity — у нас отказ при деплое (UNSUPPORTED_MULTI_INSTANCE_CONTAINER). */
    private static ContainerScenario miCallActivity() {
        String bpmn = process("f3-call-activity-mi",
                "  <bpmn:callActivity id=\"call\" name=\"call\">\n"
                        + "    <bpmn:extensionElements>\n"
                        + "      <zeebe:calledElement processId=\"" + CHILD_PROCESS_KEY + "\" />\n"
                        + "    </bpmn:extensionElements>\n"
                        + "    <bpmn:incoming>f1</bpmn:incoming>\n"
                        + "    <bpmn:outgoing>f2</bpmn:outgoing>\n"
                        + loopCharacteristics(false, false)
                        + "  </bpmn:callActivity>\n",
                "call", "call", null);
        return scenario("f3-call-activity-mi", "CALL_ACTIVITY_MULTI_INSTANCE", bpmn,
                List.of(childProcessBpmn("f3-call-activity-mi")),
                List.of(new ContainerScenario.Step("childWork", type("f3-call-activity-mi", CHILD_WORK), "{}", "a"),
                        new ContainerScenario.Step("childWork", type("f3-call-activity-mi", CHILD_WORK), "{}", "b")),
                List.of("a", "b"),
                "MI на call activity: наш движок ОТКАЗЫВАЕТ модель при деплое "
                        + "(UNSUPPORTED_MULTI_INSTANCE_CONTAINER) — что делает Zeebe 8.5?");
    }

    // =====================================================================
    // Transaction
    // =====================================================================

    /** Transaction: обычный (без отмены) и с cancel-end + cancel-границей. */
    private static ContainerScenario transaction(boolean withCancel) {
        String id = withCancel ? "f3-transaction-cancel" : "f3-transaction-plain";
        StringBuilder body = new StringBuilder();
        body.append("  <bpmn:transaction id=\"tx\" name=\"tx\">\n")
            .append("    <bpmn:incoming>f1</bpmn:incoming>\n")
            .append("    <bpmn:outgoing>f2</bpmn:outgoing>\n")
            .append("    <bpmn:startEvent id=\"txStart\"><bpmn:outgoing>tf1</bpmn:outgoing></bpmn:startEvent>\n")
            .append("    <bpmn:sequenceFlow id=\"tf1\" sourceRef=\"txStart\" targetRef=\"txWork\" />\n")
            .append(serviceTask("txWork", type(id, WORK), "tf1", "tf2"))
            .append("    <bpmn:sequenceFlow id=\"tf2\" sourceRef=\"txWork\" targetRef=\"txEnd\" />\n");
        if (withCancel) {
            body.append("    <bpmn:endEvent id=\"txEnd\">\n")
                .append("      <bpmn:incoming>tf2</bpmn:incoming>\n")
                .append("      <bpmn:cancelEventDefinition id=\"cad1\" />\n")
                .append("    </bpmn:endEvent>\n")
                .append("  </bpmn:transaction>\n")
                .append("  <bpmn:boundaryEvent id=\"cb\" name=\"cb\" attachedToRef=\"tx\">\n")
                .append("    <bpmn:outgoing>cf1</bpmn:outgoing>\n")
                .append("    <bpmn:cancelEventDefinition id=\"cad2\" />\n")
                .append("  </bpmn:boundaryEvent>\n")
                .append("  <bpmn:sequenceFlow id=\"cf1\" sourceRef=\"cb\" targetRef=\"afterCancel\" />\n")
                .append(serviceTask("afterCancel", type(id, AFTER_CANCEL), "cf1", "f4"));
        } else {
            body.append("    <bpmn:endEvent id=\"txEnd\"><bpmn:incoming>tf2</bpmn:incoming></bpmn:endEvent>\n")
                .append("  </bpmn:transaction>\n");
        }
        // Второй вход в наблюдательную задачу (f4) — только у варианта с отменой:
        // у «обычной» транзакции элемента afterCancel нет, и поток f4 повис бы
        // в вися (наш движок — UNRESOLVED_SEQUENCE_FLOW_REF, Zeebe — cvc-id.1).
        String bpmn = process(id, body.toString(), "tx", "tx", withCancel ? "afterCancel" : null);
        List<ContainerScenario.Step> steps = new ArrayList<>();
        steps.add(new ContainerScenario.Step("txWork", type(id, WORK)));
        if (withCancel) {
            // Задача ПОСЛЕ cancel-границы: без неё сценарий не дойдёт до точки
            // наблюдения (наблюдательная задача — после границы).
            steps.add(new ContainerScenario.Step("afterCancel", type(id, AFTER_CANCEL)));
        }
        return scenario(id, withCancel ? "TRANSACTION_CANCEL" : "TRANSACTION_PLAIN", bpmn, List.of(),
                steps, List.of(),
                withCancel
                        ? "Transaction с cancel-end: отмена скоупа транзакции и продолжение по "
                          + "cancel-границе."
                        : "Transaction без отмены: контейнер закрывается, поток идёт дальше.");
    }

    // =====================================================================
    // Дочерний процесс для call activity
    // =====================================================================

    /** Дочерний процесс call activity — ОТДЕЛЬНЫЙ ресурс (у нас и в Zeebe — отдельный деплой). */
    public static String childProcessBpmn(String scenarioId) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
                + "xmlns:zeebe=\"http://camunda.org/schema/zeebe/1.0\" "
                + "id=\"defs_f3_child\" targetNamespace=\"http://bpmn.io/schema/bpmn\">\n"
                + "  <bpmn:process id=\"" + CHILD_PROCESS_KEY + "\" name=\"" + CHILD_PROCESS_KEY
                + "\" isExecutable=\"true\">\n"
                + "    <bpmn:startEvent id=\"childStart\"><bpmn:outgoing>cf1</bpmn:outgoing></bpmn:startEvent>\n"
                + "    <bpmn:sequenceFlow id=\"cf1\" sourceRef=\"childStart\" targetRef=\"childWork\" />\n"
                + serviceTask("childWork", type(scenarioId, CHILD_WORK), "cf1", "cf2")
                + "    <bpmn:sequenceFlow id=\"cf2\" sourceRef=\"childWork\" targetRef=\"childEnd\" />\n"
                + "    <bpmn:endEvent id=\"childEnd\"><bpmn:incoming>cf2</bpmn:incoming></bpmn:endEvent>\n"
                + "  </bpmn:process>\n"
                + "</bpmn:definitions>\n";
    }

    // =====================================================================
    // Сборка схемы
    // =====================================================================

    /**
     * Собирает схему процесса: start → [контейнер/цепочка] → «наблюдательная» задача → end.
     *
     * <p>ПОТОКИ УЖЕ НАЗВАНЫ в теле сценария ({@code f1}, {@code f2}, …), поэтому
     * помощник только ЗАМКЫВАЕТ схему: вход из start и выход в наблюдательную
     * задачу. Идентификаторы потоков должны быть согласованы с телом: если
     * элемент объявляет {@code <bpmn:outgoing>f2</bpmn:outgoing>}, а поток f2
     * идёт от другого элемента — схема противоречива (наш парсер это
     * предупреждает, Zeebe — валидирует по XSD).
     *
     * @param scenarioId    id сценария — из него делается УНИКАЛЬНЫЙ ключ процесса
     *                      (иначе сценарии одного среза деплоили бы один и тот же
     *                      ключ и путались версии)
     * @param containerXml  тело сценария (уже с отступами)
     * @param firstTarget   id элемента, в который входит поток из start (f1)
     * @param dumpSource    id элемента, из которого идёт поток в наблюдательную
     *                      задачу (f2)
     * @param extraDumpSource id элемента, из которого идёт ВТОРОЙ поток в
     *                      наблюдательную задачу (f3) — нужно схеме с
     *                      альтернативным путём (отмена транзакции); null — нет
     * @param extraSignals  дополнительные определения сигналов (для event sub-process)
     */
    private static String process(String scenarioId, String containerXml, String firstTarget,
            String dumpSource, String extraDumpSource, String... extraSignals) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" ")
          .append("xmlns:zeebe=\"http://camunda.org/schema/zeebe/1.0\" ")
          .append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
          .append("id=\"defs_ops28f3\" targetNamespace=\"http://bpmn.io/schema/bpmn\">\n");
        for (String signal : extraSignals) {
            sb.append("  <bpmn:signal id=\"").append(signal).append("\" name=\"").append(signal)
              .append("\" />\n");
        }
        sb.append("  <bpmn:process id=\"").append(processKey(scenarioId))
          .append("\" name=\"").append(processKey(scenarioId)).append("\" isExecutable=\"true\">\n");
        sb.append("    <bpmn:startEvent id=\"start\"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>\n");
        sb.append("    <bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"").append(firstTarget).append("\" />\n");
        sb.append(containerXml);
        sb.append("    <bpmn:sequenceFlow id=\"f2\" sourceRef=\"").append(dumpSource).append("\" targetRef=\"dump\" />\n");
        if (extraDumpSource != null) {
            sb.append("    <bpmn:sequenceFlow id=\"f4\" sourceRef=\"").append(extraDumpSource).append("\" targetRef=\"dump\" />\n");
        }
        sb.append("    <bpmn:serviceTask id=\"dump\" name=\"dump\">\n")
          .append("      <bpmn:extensionElements>\n")
          .append("        <zeebe:taskDefinition type=\"").append(type(scenarioId, DUMP)).append("\" />\n")
          .append("      </bpmn:extensionElements>\n")
          .append("      <bpmn:incoming>f2</bpmn:incoming>\n");
        if (extraDumpSource != null) {
            sb.append("      <bpmn:incoming>f4</bpmn:incoming>\n");
        }
        sb.append("      <bpmn:outgoing>f3</bpmn:outgoing>\n")
          .append("    </bpmn:serviceTask>\n");
        sb.append("    <bpmn:sequenceFlow id=\"f3\" sourceRef=\"dump\" targetRef=\"end\" />\n");
        sb.append("    <bpmn:endEvent id=\"end\"><bpmn:incoming>f3</bpmn:incoming></bpmn:endEvent>\n");
        sb.append("  </bpmn:process>\n");
        sb.append("</bpmn:definitions>\n");
        return sb.toString();
    }

    /**
     * Тип job'а, УНИКАЛЬНЫЙ для сценария.
     *
     * <p>Зачем: оракул Zeebe активирует job'ы ПО ТИПУ, а не по инстансу
     * (в протоколе Zeebe 8.5 у job'а нет ссылки на родительский инстанс —
     * дочерний процесс call activity от родителя не отличить). Уникальный тип
     * на сценарий снимает риск перехвата чужого job'а: в сцене работают только
     * job'ы этого сценария. Тип одинаков на обеих сторонах (он живёт в BPMN).
     */
    public static String type(String scenarioId, String role) {
        return scenarioId.replace('-', '_') + "_" + role;
    }

    /** Уникальный ключ процесса сценария (для деплоя). */
    public static String processKey(String scenarioId) {
        return "ops28f3_" + scenarioId.replace('-', '_');
    }

    /** Service task с типом job'а. */
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
     * {@code multiInstanceLoopCharacteristics} — в диалекте Zeebe 8.5:
     * {@code zeebe:loopCharacteristics} внутри {@code extensionElements},
     * {@code completionCondition} — дочерним элементом (атрибутная форма молча
     * игнорируется Zeebe), {@code incoming/outgoing} контейнера — ПЕРЕД MI-элементом
     * (иначе XSD-валидатор Zeebe отвергает схему).
     */
    private static String loopCharacteristics(boolean sequential, boolean completionCondition) {
        StringBuilder sb = new StringBuilder();
        sb.append("    <bpmn:multiInstanceLoopCharacteristics isSequential=\"").append(sequential).append("\">\n");
        sb.append("      <bpmn:extensionElements>\n");
        sb.append("        <zeebe:loopCharacteristics inputCollection=\"=")
          .append(BpmnScenarioGenerator.COLLECTION_VAR).append("\" inputElement=\"")
          .append(BpmnScenarioGenerator.INPUT_ELEMENT).append("\" outputCollection=\"")
          .append(BpmnScenarioGenerator.OUTPUT_COLLECTION).append("\" outputElement=\"=")
          .append(BpmnScenarioGenerator.INPUT_ELEMENT).append("\" />\n");
        sb.append("      </bpmn:extensionElements>\n");
        if (completionCondition) {
            sb.append("      <bpmn:completionCondition xsi:type=\"bpmn:tFormalExpression\">")
              .append("=numberOfCompletedInstances &gt;= 1</bpmn:completionCondition>\n");
        }
        sb.append("    </bpmn:multiInstanceLoopCharacteristics>\n");
        return sb.toString();
    }

    /**
     * Собирает сценарий среза F3.
     *
     * @param collection коллекция MI-цикла (пустая — сценарий без MI)
     */
    private static ContainerScenario scenario(String id, String content, String bpmn,
            List<String> childBpmn, List<ContainerScenario.Step> steps, List<String> collection,
            String expectation) {
        List<String> taskElementIds = new ArrayList<>();
        for (ContainerScenario.Step step : steps) {
            if (!taskElementIds.contains(step.elementId())) {
                taskElementIds.add(step.elementId());
            }
        }
        List<String> jobTypes = new ArrayList<>();
        for (ContainerScenario.Step step : steps) {
            if (!jobTypes.contains(step.jobType())) {
                jobTypes.add(step.jobType());
            }
        }
        BpmnScenario base = new BpmnScenario(id, CONTAINER_AXIS, content, collection.size(), collection,
                List.of(), bpmn, itemsJson(collection), taskElementIds, "dump", 1, List.of(), jobTypes,
                type(id, DUMP));
        return new ContainerScenario(base, childBpmn, steps, expectation);
    }

    /** Стартовые переменные: коллекция MI (если инстансы адресованы по {@code item}). */
    private static String itemsJson(List<String> items) {
        if (items.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{\"").append(BpmnScenarioGenerator.COLLECTION_VAR).append("\":[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append('"').append(items.get(i)).append('"');
        }
        return sb.append("]}").toString();
    }
}
