package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-44, критерий 3 — МАТРИЦА форм XML: для каждого типа flow-узла вариант БЕЗ
 * объявленных {@code <bpmn:incoming>/<bpmn:outgoing>} проходит тот же сценарий,
 * что вариант С ними.
 *
 * <p>Построение: каждый кейс — ОДИН XML-шаблон с объявленными ссылками; вариант
 * «без» получается из него ПРОГРАММНЫМ вырезанием всех дочерних
 * {@code <bpmn:incoming>/<bpmn:outgoing>} (ровно одна ось различия — сами
 * объявления; атрибуты {@code default}/{@code attachedToRef}, потоки, расширения
 * не трогаются). Затем два утверждения:
 * <ol>
 *   <li>parse-уровень: у КАЖДОГО элемента модели списки incoming/outgoing
 *   варианта «без» равны варианту «с» (нормализация вывела их из графа);</li>
 *   <li>execution-уровень: оба варианта деплоятся, стартуют, прогоняются одним
 *   драйвером и дают ОДИНАКОВЫЙ наблюдаемый след (мультимножество
 *   {@code bpmnElementId → status}, состояние completedAt, число инцидентов).</li>
 * </ol>
 *
 * <p>Любое расхождение — отдельная строка в отчёте WO-C8-44 (не подгонка
 * ожидания: ожидание выведено из теории «граф задают sequenceFlow», §BPMN 2.0).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class UndeclaredFlowRefsMatrixIntegrationTests {

    private static final String NS =
        "xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
            + "xmlns:zeebe=\"http://camunda.org/schema/zeebe/1.0\" "
            + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"";

    @Autowired
    private ProcessDefinitionService processDefinitionService;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private ActivityService activityService;
    @Autowired
    private QueryService queryService;
    @Autowired
    private ActivityRepository activityRepository;
    @Autowired
    private IncidentRepository incidentRepository;
    @Autowired
    private BpmnParseService bpmnParseService;
    @Autowired
    private com.zorrodev.bpm.engine.handler.ElementSupport elementSupport;
    @Autowired
    private com.zorrodev.bpm.engine.repository.UserTaskRepository userTaskRepository;

    /** Контекст драйвера: действия над запущенным инстансом (реализация — сам тест). */
    interface DriverCtx {
        void userTasks(UUID pi, String... ids);

        void serviceTasks(UUID pi, String... ids);

        void correlate(UUID pi, String message);

        void childTasks(UUID pi, String childTaskId);
    }

    /** Драйвер сценария: доводит запущенный инстанс до терминального состояния. */
    @FunctionalInterface
    interface Driver {
        void drive(UUID pi, DriverCtx ctx) throws Exception;
    }

    private record Case(String name, Function<Boolean, String> xml, Driver driver) {
    }

    private static String doc(String processId, String extraDefs, String body) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<bpmn:definitions " + NS + " id=\"defs-c844-" + processId + "\""
            + " targetNamespace=\"http://bpmn.io/schema/bpmn\">"
            + extraDefs
            + "<bpmn:process id=\"" + processId + "\" name=\"" + processId + "\" isExecutable=\"true\">"
            + body
            + "</bpmn:process></bpmn:definitions>";
    }

    /** Объявления ссылок узла (через запятую, если несколько); в варианте «без» — пусто. */
    private static String refs(boolean with, String incomingCsv, String outgoingCsv) {
        if (!with) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (incomingCsv != null) {
            for (String id : incomingCsv.split(",")) {
                sb.append("<bpmn:incoming>").append(id.trim()).append("</bpmn:incoming>");
            }
        }
        if (outgoingCsv != null) {
            for (String id : outgoingCsv.split(",")) {
                sb.append("<bpmn:outgoing>").append(id.trim()).append("</bpmn:outgoing>");
            }
        }
        return sb.toString();
    }

    private static String start(boolean with) {
        return "<bpmn:startEvent id=\"start\">" + refs(with, null, "f1") + "</bpmn:startEvent>"
            + "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"node\" />";
    }

    private static String end(boolean with) {
        return "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"node\" targetRef=\"end\" />"
            + "<bpmn:endEvent id=\"end\">" + refs(with, "f2", null) + "</bpmn:endEvent>";
    }

    /** Линейный кейс start → node → end; middle — XML узла с refs(with,"f1","f2"). */
    private static Case linear(String name, Function<Boolean, String> middle, Driver driver) {
        return new Case(name,
            with -> doc("c844-" + name, "", start(with) + middle.apply(with) + end(with)),
            driver);
    }

    static Stream<Arguments> cases() {
        List<Case> all = new ArrayList<>();

        all.add(linear("serviceTask",
            with -> "<bpmn:serviceTask id=\"node\" name=\"node\">"
                + "<bpmn:extensionElements><zeebe:taskDefinition type=\"c844svc\" /></bpmn:extensionElements>"
                + refs(with, "f1", "f2") + "</bpmn:serviceTask>",
            (pi, ctx) -> ctx.serviceTasks(pi, "node")));

        all.add(linear("userTask",
            with -> "<bpmn:userTask id=\"node\" name=\"node\">" + refs(with, "f1", "f2") + "</bpmn:userTask>",
            (pi, ctx) -> ctx.userTasks(pi, "node")));

        all.add(linear("scriptTask",
            with -> "<bpmn:scriptTask id=\"node\" name=\"node\" scriptFormat=\"feel\" resultVariable=\"done\">"
                + refs(with, "f1", "f2") + "<bpmn:script>1</bpmn:script></bpmn:scriptTask>",
            (pi, ctx) -> { }));

        all.add(linear("businessRuleTask",
            with -> "<bpmn:businessRuleTask id=\"node\" name=\"node\">"
                + "<bpmn:extensionElements><zeebe:script expression=\"=1 + 1\" resultVariable=\"doubled\" /></bpmn:extensionElements>"
                + refs(with, "f1", "f2") + "</bpmn:businessRuleTask>",
            (pi, ctx) -> { }));

        all.add(linear("manualTask",
            with -> "<bpmn:manualTask id=\"node\" name=\"node\">" + refs(with, "f1", "f2") + "</bpmn:manualTask>",
            (pi, ctx) -> { }));

        all.add(linear("bareTask",
            with -> "<bpmn:task id=\"node\" name=\"node\">" + refs(with, "f1", "f2") + "</bpmn:task>",
            (pi, ctx) -> { }));

        all.add(new Case("sendTask",
            with -> doc("c844-sendTask",
                "<bpmn:message id=\"notifyMsg\" name=\"notify\" />",
                start(with)
                    + "<bpmn:sendTask id=\"node\" name=\"node\" messageRef=\"notifyMsg\">"
                    + refs(with, "f1", "f2") + "</bpmn:sendTask>"
                    + end(with)),
            (pi, ctx) -> { }));

        all.add(new Case("receiveTask",
            with -> doc("c844-receiveTask",
                "<bpmn:message id=\"approveMsg\" name=\"approve\" />",
                start(with)
                    + "<bpmn:receiveTask id=\"node\" name=\"node\" messageRef=\"approveMsg\">"
                    + refs(with, "f1", "f2") + "</bpmn:receiveTask>"
                    + end(with)),
            (pi, ctx) -> ctx.correlate(pi, "approve")));

        all.add(new Case("exclusiveGateway",
            with -> doc("c844-exclusiveGateway", "",
                "<bpmn:startEvent id=\"start\">" + refs(with, null, "f1") + "</bpmn:startEvent>"
                    + "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"node\" />"
                    + "<bpmn:exclusiveGateway id=\"node\" name=\"node\" default=\"f2\">"
                    + refs(with, "f1", "f2,f3") + "</bpmn:exclusiveGateway>"
                    + "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"node\" targetRef=\"end\" />"
                    + "<bpmn:sequenceFlow id=\"f3\" sourceRef=\"node\" targetRef=\"endOther\">"
                    + "<bpmn:conditionExpression xsi:type=\"bpmn:tFormalExpression\">=false</bpmn:conditionExpression>"
                    + "</bpmn:sequenceFlow>"
                    + "<bpmn:endEvent id=\"end\">" + refs(with, "f2", null) + "</bpmn:endEvent>"
                    + "<bpmn:endEvent id=\"endOther\">" + refs(with, "f3", null) + "</bpmn:endEvent>"),
            (pi, ctx) -> { }));

        all.add(new Case("parallelGateway",
            with -> doc("c844-parallelGateway", "",
                "<bpmn:startEvent id=\"start\">" + refs(with, null, "f1") + "</bpmn:startEvent>"
                    + "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"node\" />"
                    + "<bpmn:parallelGateway id=\"node\" name=\"node\">" + refs(with, "f1", "fA,fB") + "</bpmn:parallelGateway>"
                    + "<bpmn:sequenceFlow id=\"fA\" sourceRef=\"node\" targetRef=\"taskA\" />"
                    + "<bpmn:userTask id=\"taskA\" name=\"taskA\">" + refs(with, "fA", "fAJ") + "</bpmn:userTask>"
                    + "<bpmn:sequenceFlow id=\"fAJ\" sourceRef=\"taskA\" targetRef=\"join\" />"
                    + "<bpmn:sequenceFlow id=\"fB\" sourceRef=\"node\" targetRef=\"taskB\" />"
                    + "<bpmn:userTask id=\"taskB\" name=\"taskB\">" + refs(with, "fB", "fBJ") + "</bpmn:userTask>"
                    + "<bpmn:sequenceFlow id=\"fBJ\" sourceRef=\"taskB\" targetRef=\"join\" />"
                    + "<bpmn:parallelGateway id=\"join\" name=\"join\">" + refs(with, "fAJ,fBJ", "f2") + "</bpmn:parallelGateway>"
                    + "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"join\" targetRef=\"end\" />"
                    + "<bpmn:endEvent id=\"end\">" + refs(with, "f2", null) + "</bpmn:endEvent>"),
            (pi, ctx) -> ctx.userTasks(pi, "taskA", "taskB")));

        all.add(new Case("inclusiveGateway",
            with -> doc("c844-inclusiveGateway", "",
                "<bpmn:startEvent id=\"start\">" + refs(with, null, "f1") + "</bpmn:startEvent>"
                    + "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"node\" />"
                    + "<bpmn:inclusiveGateway id=\"node\" name=\"node\">" + refs(with, "f1", "fA,fB") + "</bpmn:inclusiveGateway>"
                    + "<bpmn:sequenceFlow id=\"fA\" sourceRef=\"node\" targetRef=\"taskA\" />"
                    + "<bpmn:userTask id=\"taskA\" name=\"taskA\">" + refs(with, "fA", "fAJ") + "</bpmn:userTask>"
                    + "<bpmn:sequenceFlow id=\"fAJ\" sourceRef=\"taskA\" targetRef=\"join\" />"
                    + "<bpmn:sequenceFlow id=\"fB\" sourceRef=\"node\" targetRef=\"taskB\" />"
                    + "<bpmn:userTask id=\"taskB\" name=\"taskB\">" + refs(with, "fB", "fBJ") + "</bpmn:userTask>"
                    + "<bpmn:sequenceFlow id=\"fBJ\" sourceRef=\"taskB\" targetRef=\"join\" />"
                    + "<bpmn:inclusiveGateway id=\"join\" name=\"join\">" + refs(with, "fAJ,fBJ", "f2") + "</bpmn:inclusiveGateway>"
                    + "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"join\" targetRef=\"end\" />"
                    + "<bpmn:endEvent id=\"end\">" + refs(with, "f2", null) + "</bpmn:endEvent>"),
            (pi, ctx) -> ctx.userTasks(pi, "taskA", "taskB")));

        all.add(new Case("eventBasedGateway",
            with -> doc("c844-eventBasedGateway",
                "<bpmn:message id=\"approveMsg\" name=\"approve\" />",
                "<bpmn:startEvent id=\"start\">" + refs(with, null, "f1") + "</bpmn:startEvent>"
                    + "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"node\" />"
                    + "<bpmn:eventBasedGateway id=\"node\" name=\"node\">" + refs(with, "f1", "fM,fT") + "</bpmn:eventBasedGateway>"
                    + "<bpmn:sequenceFlow id=\"fM\" sourceRef=\"node\" targetRef=\"msgCatch\" />"
                    + "<bpmn:intermediateCatchEvent id=\"msgCatch\" name=\"msgCatch\">"
                    + refs(with, "fM", "fM2")
                    + "<bpmn:messageEventDefinition id=\"med1\" messageRef=\"approveMsg\" /></bpmn:intermediateCatchEvent>"
                    + "<bpmn:sequenceFlow id=\"fM2\" sourceRef=\"msgCatch\" targetRef=\"endApprove\" />"
                    + "<bpmn:endEvent id=\"endApprove\">" + refs(with, "fM2", null) + "</bpmn:endEvent>"
                    + "<bpmn:sequenceFlow id=\"fT\" sourceRef=\"node\" targetRef=\"timerCatch\" />"
                    + "<bpmn:intermediateCatchEvent id=\"timerCatch\" name=\"timerCatch\">"
                    + refs(with, "fT", "fT2")
                    + "<bpmn:timerEventDefinition id=\"ted1\"><bpmn:timeDuration>PT1H</bpmn:timeDuration></bpmn:timerEventDefinition>"
                    + "</bpmn:intermediateCatchEvent>"
                    + "<bpmn:sequenceFlow id=\"fT2\" sourceRef=\"timerCatch\" targetRef=\"endTimeout\" />"
                    + "<bpmn:endEvent id=\"endTimeout\">" + refs(with, "fT2", null) + "</bpmn:endEvent>"),
            (pi, ctx) -> ctx.correlate(pi, "approve")));

        all.add(new Case("intermediateCatchMessage",
            with -> doc("c844-intermediateCatchMessage",
                "<bpmn:message id=\"pingMsg\" name=\"ping\" />",
                start(with)
                    + "<bpmn:intermediateCatchEvent id=\"node\" name=\"node\">"
                    + refs(with, "f1", "f2")
                    + "<bpmn:messageEventDefinition id=\"med1\" messageRef=\"pingMsg\" /></bpmn:intermediateCatchEvent>"
                    + end(with)),
            (pi, ctx) -> ctx.correlate(pi, "ping")));

        all.add(new Case("intermediateThrowNone",
            with -> doc("c844-intermediateThrowNone", "",
                start(with)
                    + "<bpmn:intermediateThrowEvent id=\"node\" name=\"node\">" + refs(with, "f1", "f2") + "</bpmn:intermediateThrowEvent>"
                    + end(with)),
            (pi, ctx) -> { }));

        all.add(new Case("boundaryTimer",
            with -> doc("c844-boundaryTimer", "",
                "<bpmn:startEvent id=\"start\">" + refs(with, null, "f1") + "</bpmn:startEvent>"
                    + "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"node\" />"
                    + "<bpmn:userTask id=\"node\" name=\"node\">" + refs(with, "f1", "f2") + "</bpmn:userTask>"
                    + "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"node\" targetRef=\"end\" />"
                    + "<bpmn:endEvent id=\"end\">" + refs(with, "f2", null) + "</bpmn:endEvent>"
                    + "<bpmn:boundaryEvent id=\"bnd\" name=\"bnd\" attachedToRef=\"node\" cancelActivity=\"false\">"
                    + refs(with, null, "fB")
                    + "<bpmn:timerEventDefinition id=\"tedB\"><bpmn:timeDuration>PT5M</bpmn:timeDuration></bpmn:timerEventDefinition>"
                    + "</bpmn:boundaryEvent>"
                    + "<bpmn:sequenceFlow id=\"fB\" sourceRef=\"bnd\" targetRef=\"bndTask\" />"
                    + "<bpmn:userTask id=\"bndTask\" name=\"bndTask\">" + refs(with, "fB", null) + "</bpmn:userTask>"),
            (pi, ctx) -> ctx.userTasks(pi, "node")));

        all.add(new Case("callActivity",
            with -> doc("c844-callActivity", "",
                start(with)
                    + "<bpmn:callActivity id=\"node\" name=\"node\">"
                    + "<bpmn:extensionElements><zeebe:calledElement processId=\"c844-call-child\" propagateAllParentVariables=\"false\" /></bpmn:extensionElements>"
                    + refs(with, "f1", "f2") + "</bpmn:callActivity>"
                    + end(with)),
            (pi, ctx) -> ctx.childTasks(pi, "childWork")));

        all.add(new Case("subProcess",
            with -> doc("c844-subProcess", "",
                "<bpmn:startEvent id=\"start\">" + refs(with, null, "f1") + "</bpmn:startEvent>"
                    + "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"node\" />"
                    + "<bpmn:subProcess id=\"node\" name=\"node\">"
                    + refs(with, "f1", "f2")
                    + "<bpmn:startEvent id=\"subStart\">" + refs(with, null, "sf1") + "</bpmn:startEvent>"
                    + "<bpmn:sequenceFlow id=\"sf1\" sourceRef=\"subStart\" targetRef=\"inner\" />"
                    + "<bpmn:userTask id=\"inner\" name=\"inner\">" + refs(with, "sf1", "sf2") + "</bpmn:userTask>"
                    + "<bpmn:sequenceFlow id=\"sf2\" sourceRef=\"inner\" targetRef=\"subEnd\" />"
                    + "<bpmn:endEvent id=\"subEnd\">" + refs(with, "sf2", null) + "</bpmn:endEvent>"
                    + "</bpmn:subProcess>"
                    + "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"node\" targetRef=\"end\" />"
                    + "<bpmn:endEvent id=\"end\">" + refs(with, "f2", null) + "</bpmn:endEvent>"),
            (pi, ctx) -> ctx.userTasks(pi, "inner")));

        all.add(new Case("miSubProcess",
            with -> doc("c844-miSubProcess", "",
                "<bpmn:startEvent id=\"start\">" + refs(with, null, "f1") + "</bpmn:startEvent>"
                    + "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"node\" />"
                    + "<bpmn:subProcess id=\"node\" name=\"node\">"
                    + "<bpmn:multiInstanceLoopCharacteristics isSequential=\"false\">"
                    + "<bpmn:extensionElements>"
                    + "<zeebe:loopCharacteristics inputCollection=\"=[&quot;a&quot;, &quot;b&quot;]\" inputElement=\"item\""
                    + " outputCollection=\"results\" outputElement=\"=item\" />"
                    + "</bpmn:extensionElements></bpmn:multiInstanceLoopCharacteristics>"
                    + refs(with, "f1", "f2")
                    + "<bpmn:startEvent id=\"subStart\">" + refs(with, null, "sf1") + "</bpmn:startEvent>"
                    + "<bpmn:sequenceFlow id=\"sf1\" sourceRef=\"subStart\" targetRef=\"inner\" />"
                    + "<bpmn:userTask id=\"inner\" name=\"inner\">"
                    + "<bpmn:extensionElements><zeebe:assignmentDefinition assignee=\"=item\" /></bpmn:extensionElements>"
                    + refs(with, "sf1", "sf2") + "</bpmn:userTask>"
                    + "<bpmn:sequenceFlow id=\"sf2\" sourceRef=\"inner\" targetRef=\"subEnd\" />"
                    + "<bpmn:endEvent id=\"subEnd\">" + refs(with, "sf2", null) + "</bpmn:endEvent>"
                    + "</bpmn:subProcess>"
                    + "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"node\" targetRef=\"end\" />"
                    + "<bpmn:endEvent id=\"end\">" + refs(with, "f2", null) + "</bpmn:endEvent>"),
            (pi, ctx) -> ctx.userTasks(pi, "inner")));

        all.add(new Case("eventSubProcess",
            with -> doc("c844-eventSubProcess",
                "<bpmn:message id=\"msgCancel\" name=\"cancelOrder\" />",
                "<bpmn:startEvent id=\"start\">" + refs(with, null, "f1") + "</bpmn:startEvent>"
                    + "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"mainTask\" />"
                    + "<bpmn:userTask id=\"mainTask\" name=\"mainTask\">" + refs(with, "f1", "f2") + "</bpmn:userTask>"
                    + "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"mainTask\" targetRef=\"mainEnd\" />"
                    + "<bpmn:endEvent id=\"mainEnd\">" + refs(with, "f2", null) + "</bpmn:endEvent>"
                    + "<bpmn:subProcess id=\"node\" name=\"node\" triggeredByEvent=\"true\">"
                    + "<bpmn:startEvent id=\"evStart\" name=\"evStart\" isInterrupting=\"true\">"
                    + refs(with, null, "flowE1")
                    + "<bpmn:messageEventDefinition id=\"med1\" messageRef=\"msgCancel\" /></bpmn:startEvent>"
                    + "<bpmn:sequenceFlow id=\"flowE1\" sourceRef=\"evStart\" targetRef=\"evEnd\" />"
                    + "<bpmn:endEvent id=\"evEnd\" name=\"evEnd\">" + refs(with, "flowE1", null) + "</bpmn:endEvent>"
                    + "</bpmn:subProcess>"),
            (pi, ctx) -> ctx.correlate(pi, "cancelOrder")));

        all.add(new Case("adHocSubProcess",
            with -> doc("c844-adHocSubProcess", "",
                "<bpmn:startEvent id=\"start\">" + refs(with, null, "f1") + "</bpmn:startEvent>"
                    + "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"node\" />"
                    + "<bpmn:adHocSubProcess id=\"node\" name=\"node\">"
                    + refs(with, "f1", "f2")
                    + "<bpmn:extensionElements><zeebe:adHoc activeElementsCollection=\"=[&quot;taskA&quot;, &quot;taskB&quot;]\" /></bpmn:extensionElements>"
                    + "<bpmn:userTask id=\"taskA\" name=\"taskA\" />"
                    + "<bpmn:userTask id=\"taskB\" name=\"taskB\" />"
                    + "</bpmn:adHocSubProcess>"
                    + "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"node\" targetRef=\"end\" />"
                    + "<bpmn:endEvent id=\"end\">" + refs(with, "f2", null) + "</bpmn:endEvent>"),
            (pi, ctx) -> ctx.userTasks(pi, "taskA", "taskB")));

        return all.stream().map(c -> Arguments.of(c.name(), c));
    }

    // ── исполнение матрицы ───────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}: without-declared-refs behaves like with-declared-refs")
    @MethodSource("cases")
    @Transactional
    void undeclaredRefs_behavesLikeDeclared(String name, Case c) throws Exception {
        String withXml = c.xml().apply(true);
        String withoutXml = c.xml().apply(false);

        // (1) parse-уровень: нормализация выводит те же списки из графа.
        BpmnProcessDefinitionModel withModel = bpmnParseService.parse(withXml);
        BpmnProcessDefinitionModel withoutModel = bpmnParseService.parse(withoutXml);
        Map<String, List<String>> withRefs = refsOf(withModel);
        Map<String, List<String>> withoutRefs = refsOf(withoutModel);
        assertThat(withoutRefs)
            .as("[%s] incoming/outgoing всех элементов без объявлений = с объявлениями", name)
            .isEqualTo(withRefs);
        assertThat(withoutModel.getFlows()).as("[%s] потоки на месте", name).hasSize(withModel.getFlows().size());

        // (2) execution-уровень: одинаковый наблюдаемый след.
        DriverCtx ctx = new DriverCtx() {
            @Override
            public void userTasks(UUID pi, String... ids) {
                completeUserTasks(pi, ids);
            }

            @Override
            public void serviceTasks(UUID pi, String... ids) {
                completeServiceTasks(pi, ids);
            }

            @Override
            public void correlate(UUID pi, String message) {
                UndeclaredFlowRefsMatrixIntegrationTests.this.correlate(pi, message);
            }

            @Override
            public void childTasks(UUID pi, String childTaskId) {
                completeChildUserTasks(pi, childTaskId);
            }
        };
        UUID withPi = start(withXml);
        c.driver().drive(withPi, ctx);
        Trace withTrace = trace(withPi);

        UUID withoutPi = start(withoutXml);
        c.driver().drive(withoutPi, ctx);
        Trace withoutTrace = trace(withoutPi);

        assertThat(withoutTrace.completed()).as("[%s] completedAt без объявлений", name).isEqualTo(withTrace.completed());
        assertThat(withoutTrace.activities()).as("[%s] след activity без объявлений", name).isEqualTo(withTrace.activities());
        assertThat(withoutTrace.incidents()).as("[%s] инциденты без объявлений", name).isEqualTo(withTrace.incidents());
        assertThat(withTrace.completed()).as("[%s] сценарий завершается (контроль)", name).isTrue();
    }

    // ── инфраструктура теста ─────────────────────────────────────────────────

    private record Trace(boolean completed, Map<String, Map<String, Long>> activities, long incidents) {
    }

    private UUID start(String bpmn) {
        // callActivity-кейс: дочернее определение деплоится первым (тот же файл
        // во всех прогонах — повторный деплой того же ключа версионирует).
        if (bpmn.contains("c844-call-child")) {
            processDefinitionService.addProcessDefinition(
                "<bpmn:definitions " + NS + " id=\"defs-c844-call-child\""
                    + " targetNamespace=\"http://bpmn.io/schema/bpmn\">"
                    + "<bpmn:process id=\"c844-call-child\" name=\"c844-call-child\" isExecutable=\"true\">"
                    + "<bpmn:startEvent id=\"cstart\"><bpmn:outgoing>cf1</bpmn:outgoing></bpmn:startEvent>"
                    + "<bpmn:sequenceFlow id=\"cf1\" sourceRef=\"cstart\" targetRef=\"childWork\" />"
                    + "<bpmn:userTask id=\"childWork\" name=\"childWork\">"
                    + "<bpmn:incoming>cf1</bpmn:incoming><bpmn:outgoing>cf2</bpmn:outgoing></bpmn:userTask>"
                    + "<bpmn:sequenceFlow id=\"cf2\" sourceRef=\"childWork\" targetRef=\"cend\" />"
                    + "<bpmn:endEvent id=\"cend\"><bpmn:incoming>cf2</bpmn:incoming></bpmn:endEvent>"
                    + "</bpmn:process></bpmn:definitions>");
        }
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of());
        return runtimeService.startProcessInstance(dto).getId();
    }

    private Trace trace(UUID pi) {
        boolean completed = queryService.getProcessInstance(pi).getCompletedAt() != null;
        Map<String, Map<String, Long>> acts = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .collect(Collectors.groupingBy(ActivityEntity::getBpmnElementId,
                TreeMap::new,
                Collectors.groupingBy(a -> a.getStatus().name(),
                    TreeMap::new, Collectors.counting())));
        var activityIds = activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .map(ActivityEntity::getId)
            .collect(Collectors.toSet());
        long incidents = incidentRepository.findAll().stream()
            .filter(i -> activityIds.contains(i.getActivityId()))
            .count();
        return new Trace(completed, acts, incidents);
    }

    private static Map<String, List<String>> refsOf(BpmnProcessDefinitionModel model) {
        Map<String, List<String>> out = new TreeMap<>();
        for (BpmnElementModel e : model.getElements()) {
            out.put(e.getId() + "#in", new ArrayList<>(e.getIncoming()));
            out.put(e.getId() + "#out", new ArrayList<>(e.getOutgoing()));
        }
        return out;
    }

    private List<ActivityEntity> live(UUID pi, String bpmnElementId) {
        return activityRepository.findAll().stream()
            .filter(a -> a.getProcessInstanceId().equals(pi))
            .filter(a -> a.getBpmnElementId().equals(bpmnElementId) && a.getStatus() == ActivityStatus.CREATED)
            .toList();
    }

    private void completeUserTasks(UUID pi, String... ids) {
        for (String id : ids) {
            for (ActivityEntity a : live(pi, id)) {
                runtimeService.completeUserTask(a.getId(), List.of());
            }
        }
    }

    private void completeServiceTasks(UUID pi, String... ids) {
        for (String id : ids) {
            for (ActivityEntity a : live(pi, id)) {
                runtimeService.completeServiceTask(a.getId(), List.of());
            }
        }
    }

    private void correlate(UUID pi, String message) {
        activityService.correlateMessage(message, pi, List.of());
    }

    private void completeChildUserTasks(UUID pi, String childTaskId) {
        com.zorrodev.bpm.engine.repository.ProcessInstanceRepository repo =
            applicationContext.getBean(com.zorrodev.bpm.engine.repository.ProcessInstanceRepository.class);
        List<com.zorrodev.bpm.engine.entity.ProcessInstanceEntity> children = repo.findAll(
            com.zorrodev.bpm.engine.repository.ProcessInstanceRepository.byParentProcessInstanceId(pi));
        assertThat(children).as("child instances started").isNotEmpty();
        for (var child : children) {
            activityRepository.findAll().stream()
                .filter(a -> a.getProcessInstanceId().equals(child.getId()))
                .filter(a -> childTaskId.equals(a.getBpmnElementId()) && a.getStatus() == ActivityStatus.CREATED)
                .forEach(a -> runtimeService.completeUserTask(a.getId(), List.of()));
        }
    }

    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;
}
