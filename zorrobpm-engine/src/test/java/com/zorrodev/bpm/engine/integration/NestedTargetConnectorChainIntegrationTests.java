package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.TokenRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-58, шаг 2 — вложенный target в движке и ВЛОЖЕННЫЙ completion-контекст
 * коннектора.
 *
 * <p>Две половины контракта, обе на ЖИВОМ пути (G-N — не копия логики в тесте):
 *
 * <ol>
 *   <li><b>Движок.</b> Target с точкой создаёт вложенный контекст (Zeebe 8.5) — и этот
 *       контекст ВИДИМ выражению того же элемента: output-mapping читает
 *       {@code =a.b} и получает значение, записанное input-mapping'ом.</li>
 *   <li><b>Коннектор → движок.</b> Completion-переменные коннектора публикуются
 *       вложенным контекстом {@code http} — поэтому {@code =http.status} в
 *       output-mapping РАЗРЕШАЕТСЯ (до WO это был null: FEEL искал контекст
 *       {@code http}, а ключ был плоским). Форма контекста — ровно та, которую
 *       возвращает {@code HttpConnectorWorker} (проверено в модуле коннектора).</li>
 * </ol>
 *
 * <p>Матрица паритета — {@code docs/analysis/feel-iomapping-conformance-matrix.md}
 * (строки 61 и 95–100), живые замеры 8.5 — {@code docs/analysis/c8-oracle/feel-iomapping-static-nested.js}.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class NestedTargetConnectorChainIntegrationTests {

    /** Контекст `http` ровно такой формы, какую возвращает HttpConnectorWorker. */
    private static final String CONNECTOR_HTTP_CONTEXT =
        "{\"status\":200,\"headers\":{\"content-type\":\"application/json\"},\"body\":{\"paid\":true}}";

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private VariableRepository variableRepository;

    @Autowired
    private TokenRepository tokenRepository;

    @Autowired
    private QueryService queryService;

    private static String bpmn() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
            + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
            + "xmlns:zeebe=\"http://camunda.org/schema/zeebe/1.0\" "
            + "id=\"Definitions_c858\" targetNamespace=\"http://bpmn.io/schema/bpmn\">"
            + "<bpmn:process id=\"c858_chain\" name=\"c858_chain\" isExecutable=\"true\">"
            + "<bpmn:startEvent id=\"start\"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>"
            // Элемент 1: вложенный input-target и выход, читающий его.
            + "<bpmn:serviceTask id=\"work\" name=\"work\">"
            + "<bpmn:extensionElements><zeebe:taskDefinition type=\"c858Work\"/>"
            + "<zeebe:ioMapping>"
            + "<zeebe:input source=\"=1\" target=\"a.b\"/>"
            + "<zeebe:output source=\"=a.b\" target=\"o\"/>"
            + "</zeebe:ioMapping></bpmn:extensionElements>"
            + "<bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing></bpmn:serviceTask>"
            // Элемент 2: выходы, читающие ВЛОЖЕННЫЙ completion-контекст коннектора.
            + "<bpmn:serviceTask id=\"conn\" name=\"conn\">"
            + "<bpmn:extensionElements><zeebe:taskDefinition type=\"c858Conn\"/>"
            + "<zeebe:ioMapping>"
            + "<zeebe:output source=\"=http.status\" target=\"status\"/>"
            + "<zeebe:output source=\"=http.body.paid\" target=\"paid\"/>"
            + "</zeebe:ioMapping></bpmn:extensionElements>"
            + "<bpmn:incoming>f2</bpmn:incoming><bpmn:outgoing>f3</bpmn:outgoing></bpmn:serviceTask>"
            + "<bpmn:serviceTask id=\"dump\" name=\"dump\">"
            + "<bpmn:extensionElements><zeebe:taskDefinition type=\"c858Dump\"/></bpmn:extensionElements>"
            + "<bpmn:incoming>f3</bpmn:incoming><bpmn:outgoing>f4</bpmn:outgoing></bpmn:serviceTask>"
            + "<bpmn:endEvent id=\"end\"><bpmn:incoming>f4</bpmn:incoming></bpmn:endEvent>"
            + "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"work\"/>"
            + "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"work\" targetRef=\"conn\"/>"
            + "<bpmn:sequenceFlow id=\"f3\" sourceRef=\"conn\" targetRef=\"dump\"/>"
            + "<bpmn:sequenceFlow id=\"f4\" sourceRef=\"dump\" targetRef=\"end\"/>"
            + "</bpmn:process></bpmn:definitions>";
    }

    private UUID start() {
        ProcessDefinition definition = processDefinitionService.addProcessDefinition(bpmn());
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(definition.getId());
        return runtimeService.startProcessInstance(dto).getId();
    }

    private ActivityEntity active(UUID pi, String elementId) {
        return activityRepository.findAll().stream()
            .filter(a -> pi.equals(a.getProcessInstanceId()))
            .filter(a -> elementId.equals(a.getBpmnElementId()))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED || a.getStatus() == ActivityStatus.IN_PROGRESS)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no active activity '" + elementId + "'"));
    }

    private static ProcessVariable json(String name, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(ProcessVariableType.JSON);
        v.setValue(value);
        return v;
    }

    /**
     * Вложенный input-target виден выражению ТОГО ЖЕ элемента, и вложенный
     * completion-контекст коннектора разрешается в output-mapping.
     */
    @Transactional
    @Test
    void nestedTargetAndConnectorCompletionContext_areResolvedByOutputMappings() {
        UUID pi = start();

        // Элемент 1: input-mapping пишет вложенный контекст a:{b:1}; его же читает
        // output-mapping того же элемента (=a.b → o).
        runtimeService.completeServiceTask(active(pi, "work").getId(), List.of());

        // Элемент 2: completion-контекст коннектора (вложенный http) — читают выходы.
        runtimeService.completeServiceTask(active(pi, "conn").getId(),
            List.of(json("http", CONNECTOR_HTTP_CONTEXT)));

        assertThat(variableRepository.findByNameAndProcessInstanceId("o", pi))
            .get().extracting("textValue").isEqualTo("1");
        assertThat(variableRepository.findByNameAndProcessInstanceId("status", pi))
            .get().extracting("textValue").isEqualTo("200");
        assertThat(variableRepository.findByNameAndProcessInstanceId("paid", pi))
            .get().extracting("textValue").isEqualTo("true");
    }

    /**
     * СОХРАНЁННОЕ СОСТОЯНИЕ живых инстансов: completion-контекст приходит ПЛОСКИМ
     * (как у уже запущенных на проде {@code connNative}/{@code connCamunda}) — вложенный
     * путь тогда НЕ разрешается (FEEL ищет контекст {@code http}, плоский ключ ему не
     * контекст): значение — JSON null, ровно как до WO. Это НЕ регрессия, и именно
     * поэтому коннектор публикует вложенный контекст ДОПОЛНИТЕЛЬНО к плоскому.
     */
    @Transactional
    @Test
    void flatCompletionVariables_doNotResolveTheNestedPath() {
        UUID pi = start();

        runtimeService.completeServiceTask(active(pi, "work").getId(), List.of());
        runtimeService.completeServiceTask(active(pi, "conn").getId(),
            List.of(json("http", CONNECTOR_HTTP_CONTEXT)));

        // Тот же элемент, но с ПЛОСКИМ completion-ключом http.status.
        UUID piFlat = start();
        runtimeService.completeServiceTask(active(piFlat, "work").getId(), List.of());
        ProcessVariable flatStatus = new ProcessVariable();
        flatStatus.setName("http.status");
        flatStatus.setType(ProcessVariableType.LONG);
        flatStatus.setValue("200");
        runtimeService.completeServiceTask(active(piFlat, "conn").getId(), List.of(flatStatus));

        assertThat(variableRepository.findByNameAndProcessInstanceId("status", pi))
            .get().extracting("textValue").isEqualTo("200");
        // Плоский ключ не разрешается во вложенный путь: значение — JSON null
        // (семантика live 8.5 для «нет такого контекста»), а НЕ 200.
        assertThat(variableRepository.findByNameAndProcessInstanceId("status", piFlat))
            .get().extracting("textValue").isEqualTo("null");
    }

    /**
     * G-P, инвариант утечек: вложенный target меняет исполнение BPMN (запись
     * переменных), поэтому после прохождения проверяем, что не осталось НИЧЕГО живого:
     * инстанс завершён, ни одной activity в CREATED/IN_PROGRESS, ни одного токена.
     *
     * <p>Особенность этого WO: вложенный контекст пишется в ТОТ ЖЕ скоуп, что и
     * раньше (плоский ключ → плоский ключ, вложенный → родитель), поэтому число
     * переменных и их жизнь не меняются — меняется только ФОРМА имени.
     */
    @Transactional
    @Test
    void nestedTarget_leavesNoLeftovers() {
        UUID pi = start();

        runtimeService.completeServiceTask(active(pi, "work").getId(), List.of());
        runtimeService.completeServiceTask(active(pi, "conn").getId(),
            List.of(json("http", CONNECTOR_HTTP_CONTEXT)));
        runtimeService.completeServiceTask(active(pi, "dump").getId(), List.of());

        assertThat(queryService.getProcessInstance(pi).getCompletedAt())
            .as("the instance is finished").isNotNull();
        assertThat(activityRepository.findAll().stream()
            .filter(a -> pi.equals(a.getProcessInstanceId()))
            .filter(a -> a.getStatus() == ActivityStatus.CREATED || a.getStatus() == ActivityStatus.IN_PROGRESS)
            .toList())
            .as("no activity left active").isEmpty();
        // Строка токена остаётся В ИСТОРИИ (fk_activities__token; «TERMINAL-ROW INVARIANT»
        // в DBService — токен с историей не удаляется). Живым считается неразобранный
        // форк: pendingBranches > 0. У завершённого инстанса таких быть не должно.
        List<UUID> tokenIds = activityRepository.findAll().stream()
            .filter(a -> pi.equals(a.getProcessInstanceId()))
            .map(ActivityEntity::getToken)
            .filter(java.util.Objects::nonNull)
            .toList();
        assertThat(tokenRepository.findAllById(tokenIds))
            .as("no unconsumed fork left in the token counters")
            .allSatisfy(t -> assertThat(t.getPendingBranches() == null || t.getPendingBranches() <= 0)
                .as("token %s has pending branches", t.getId())
                .isTrue());
    }
}
