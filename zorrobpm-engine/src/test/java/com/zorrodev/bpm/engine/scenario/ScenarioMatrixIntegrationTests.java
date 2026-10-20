package com.zorrodev.bpm.engine.scenario;

import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.handler.ElementSupport;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-OPS-27: матрица сгенерированных BPMN-сценариев — прогон на нашем движке.
 *
 * <p>Что проверяется (проверки (а)–(г) из WO):
 * <ul>
 *   <li><b>(а) инвариант утечек</b> (WO-OPS-26): после завершения инстанса не остаётся
 *       живых activity/задач/таймеров/подписок/arrival-строк;</li>
 *   <li><b>(б) терминальное состояние</b>: инстанс дошёл до COMPLETED, инцидентов нет;</li>
 *   <li><b>(в) переменные</b> — сравниваются с эталоном Zeebe 8.5 (файл ожиданий
 *       {@code scenario-matrix/zeebe-expectations.json}, снимается живым прогоном
 *       против оракула; расхождения — в явном списке известных, иначе тест краснеет);</li>
 *   <li><b>(г) независимость от порядка</b>: у параллельных контейнеров все перестановки
 *       порядка завершения дают ОДИН И ТОТ ЖЕ набор переменных.</li>
 * </ul>
 *
 * <p>Каждый сценарий идёт в СВОЕЙ транзакции ({@code REQUIRES_NEW}): падение одного
 * сценария не отравляет транзакцию следующих (иначе один сбой превращается в
 * «всё красное» и список расхождений теряется).
 *
 * <p>Артефакты прогона (для дифференциального режима и для отчёта):
 * {@code target/scenario-matrix/manifest.json} (схемы) и
 * {@code target/scenario-matrix/ours.json} (наблюдения).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class ScenarioMatrixIntegrationTests {

    /** Каталог артефактов прогона (внутри target/ — сборочный артефакт, не коммитится). */
    public static final Path ARTIFACT_DIR = Path.of("target", "scenario-matrix");

    @Autowired
    private ProcessDefinitionService processDefinitionService;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private QueryService queryService;
    @Autowired
    private ActivityRepository activityRepository;
    @Autowired
    private VariableRepository variableRepository;
    @Autowired
    private IncidentRepository incidentRepository;
    @Autowired
    private ElementSupport elementSupport;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void generatedMatrix_terminatesCleanly_andIsOrderIndependent() throws Exception {
        List<BpmnScenario> scenarios = BpmnScenarioGenerator.generate();
        List<ScenarioOutcome> outcomes = new ArrayList<>();
        for (BpmnScenario scenario : scenarios) {
            outcomes.add(runInOwnTransaction(scenario));
        }
        writeArtifacts(scenarios, outcomes);

        List<String> problems = new ArrayList<>();
        for (ScenarioOutcome outcome : outcomes) {
            if (!outcome.completed()) {
                problems.add(outcome.scenarioId() + ": terminal status=" + outcome.status()
                        + (outcome.error().isEmpty() ? "" : " error=" + outcome.error()));
            }
            if (!outcome.incidents().isEmpty()) {
                problems.add(outcome.scenarioId() + ": incidents=" + outcome.incidents());
            }
            for (String leak : outcome.leaks()) {
                problems.add(outcome.scenarioId() + ": LEAK " + leak);
            }
        }
        problems.addAll(orderIndependenceProblems(outcomes));

        assertThat(problems)
                .as("matrix problems (scenarios=%d, outcomes=%d)", scenarios.size(), outcomes.size())
                .isEmpty();
    }

    /**
     * Дифференциальная сверка с ЖИВЫМ Zeebe 8.5.0 — но без живого Zeebe в CI.
     *
     * <p>Факты оракула «прибиты» в {@code scenario-matrix/zeebe-expectations.json}
     * (сняты прогоном {@code docs/analysis/c8-oracle/scenario-matrix.js} против
     * стенда {@code zbpm-cmp-zeebe}; пересобрать — команда в отчёте). Здесь мы
     * сравниваем НАШ прогон с этими фактами: любое новое расхождение роняет CI.
     *
     * <p>Сценарии, где оракул 8.5 не исполнил сценарий (user task: Zeebe 8.5 не
     * создаёт job'а — измерено живьём), помечены в ожиданиях как
     * {@code UNSUPPORTED-8.5}: для них проверяется наше поведение (инстанс
     * завершается, утечек нет) и ТОЛЬКО ТО, что оракул остался на элементе.
     */
    @Test
    void generatedMatrix_matchesPinnedZeebe85Facts() throws Exception {
        Map<String, Map<String, Object>> expectations = loadExpectations();
        assertThat(expectations)
                .as("pinned Zeebe 8.5 facts (scenario-matrix/zeebe-expectations.json)")
                .isNotEmpty();

        List<BpmnScenario> scenarios = BpmnScenarioGenerator.generate();
        List<String> problems = new ArrayList<>();
        int compared = 0;
        for (BpmnScenario scenario : scenarios) {
            Map<String, Object> expected = expectations.get(scenario.id());
            if (expected == null) {
                problems.add(scenario.id() + ": нет прибитого факта оракула — пересними эталон");
                continue;
            }
            ScenarioOutcome outcome = runInOwnTransaction(scenario);
            if (!outcome.completed()) {
                problems.add(scenario.id() + ": наш терминальный статус=" + outcome.status()
                        + (outcome.error().isEmpty() ? "" : " error=" + outcome.error()));
                continue;
            }
            if (!outcome.leaks().isEmpty()) {
                problems.add(scenario.id() + ": LEAK " + outcome.leaks());
            }
            if ("UNSUPPORTED-8.5".equals(expected.get("oracle"))) {
                // Оракул 8.5 этот сценарий не исполнил (user task без job'а):
                // сверять нечего — фиксируем наше поведение и идём дальше.
                continue;
            }
            compared++;
            Map<String, Object> oracleVars = (Map<String, Object>) expected.get("vars");
            for (String key : oracleVars.keySet()) {
                Object want = oracleVars.get(key);
                Object got = normalize(outcome.rootVars().get(key));
                if (!String.valueOf(want).equals(String.valueOf(got))) {
                    problems.add(scenario.id() + ": " + key + " ours=" + got + " zeebe8.5=" + want);
                }
            }
            for (String key : outcome.rootVars().keySet()) {
                if (!oracleVars.containsKey(key)) {
                    problems.add(scenario.id() + ": лишняя переменная " + key + "=" + outcome.rootVars().get(key)
                            + " (в Zeebe 8.5 её нет)");
                }
            }
        }
        assertThat(problems)
                .as("divergence from the pinned Zeebe 8.5 facts (compared=%d)", compared)
                .isEmpty();
    }

    /** Прибитые факты оракула (classpath-ресурс, снимается живым прогоном). */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> loadExpectations() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (java.io.InputStream in = getClass().getResourceAsStream("/scenario-matrix/zeebe-expectations.json")) {
            assertThat(in).as("scenario-matrix/zeebe-expectations.json in classpath").isNotNull();
            Map<String, Object> root = mapper.readValue(in, Map.class);
            return (Map<String, Map<String, Object>>) root.get("scenarios");
        }
    }

    /**
     * Наше значение переменной — сырой текст ("[\"a\"]"), у оракула — разобранный
     * JSON. Приводим к общему виду: разбираем то, что разбирается, иначе строка.
     */
    private Object normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            try {
                return new ObjectMapper().readValue(trimmed, Object.class);
            } catch (Exception e) {
                return raw;
            }
        }
        return raw;
    }

    /** Каждый сценарий — в своей транзакции (сбой одного не отравляет остальные). */
    private ScenarioOutcome runInOwnTransaction(BpmnScenario scenario) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        try {
            return template.execute(status -> {
                ScenarioMatrixRunner runner = new ScenarioMatrixRunner(processDefinitionService,
                        runtimeService, queryService, activityRepository, variableRepository,
                        incidentRepository, elementSupport, jdbcTemplate);
                return runner.run(scenario);
            });
        } catch (RuntimeException e) {
            return new ScenarioOutcome(scenario.id(), scenario.container(), scenario.content(),
                    scenario.cardinality(), scenario.orderKey(), "FAILED", Map.of(), List.of(), List.of(),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * (г) Независимость от порядка: у параллельных контейнеров все перестановки
     * порядка завершения инстансов дают одинаковый набор переменных корневого скоупа.
     */
    private List<String> orderIndependenceProblems(List<ScenarioOutcome> outcomes) {
        Map<String, List<ScenarioOutcome>> groups = new LinkedHashMap<>();
        for (ScenarioOutcome outcome : outcomes) {
            if (!outcome.container().contains("PARALLEL")) {
                continue;
            }
            groups.computeIfAbsent(outcome.container() + "/" + outcome.content() + "/n" + outcome.cardinality(),
                    k -> new ArrayList<>()).add(outcome);
        }
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, List<ScenarioOutcome>> group : groups.entrySet()) {
            List<ScenarioOutcome> members = group.getValue();
            if (members.size() < 2) {
                continue;
            }
            ScenarioOutcome reference = members.get(0);
            for (ScenarioOutcome other : members.subList(1, members.size())) {
                if (!reference.rootVars().equals(other.rootVars())) {
                    problems.add("ORDER-DEPENDENT " + group.getKey()
                            + ": order[" + reference.order() + "]=" + reference.rootVars()
                            + " != order[" + other.order() + "]=" + other.rootVars());
                }
            }
        }
        return problems;
    }

    /** Пишет артефакты прогона: схемы (для оракула) и наблюдения (для сравнения). */
    private void writeArtifacts(List<BpmnScenario> scenarios, List<ScenarioOutcome> outcomes) throws Exception {
        Files.createDirectories(ARTIFACT_DIR);
        ObjectMapper mapper = new ObjectMapper();

        List<Map<String, Object>> manifest = new ArrayList<>();
        for (BpmnScenario scenario : scenarios) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", scenario.id());
            row.put("container", scenario.container());
            row.put("content", scenario.content());
            row.put("cardinality", scenario.cardinality());
            row.put("items", scenario.items());
            row.put("order", scenario.order());
            row.put("bpmn", scenario.bpmn());
            row.put("vars", scenario.varsJson());
            row.put("taskElementIds", scenario.taskElementIds());
            row.put("dumpElementId", scenario.dumpElementId());
            row.put("steps", scenario.steps());
            row.put("userTaskElementIds", scenario.userTaskElementIds());
            row.put("jobTypes", scenario.jobTypes());
            row.put("dumpJobType", scenario.dumpJobType());
            manifest.add(row);
        }
        Files.writeString(ARTIFACT_DIR.resolve("manifest.json"),
                mapper.writeValueAsString(Map.of("scenarios", manifest)));

        List<Map<String, Object>> observed = new ArrayList<>();
        for (ScenarioOutcome outcome : outcomes) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", outcome.scenarioId());
            row.put("container", outcome.container());
            row.put("content", outcome.content());
            row.put("cardinality", outcome.cardinality());
            row.put("order", outcome.order());
            row.put("status", outcome.status());
            row.put("rootVars", outcome.rootVars());
            row.put("leaks", outcome.leaks());
            row.put("incidents", outcome.incidents());
            row.put("error", outcome.error());
            observed.add(row);
        }
        Files.writeString(ARTIFACT_DIR.resolve("ours.json"),
                mapper.writeValueAsString(Map.of("engine", "zorro", "outcomes", observed)));
    }

}
