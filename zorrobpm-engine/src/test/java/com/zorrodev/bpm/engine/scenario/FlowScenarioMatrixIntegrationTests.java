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
 * WO-OPS-28, ФАЗА 2 («потоки»): матрица сгенерированных BPMN-сценариев среза
 * «потоки» против ЖИВОГО Zeebe 8.5.0.
 *
 * <p><b>Что этот тест защищает.</b> Он НЕ чинит расхождения (по WO баги
 * чинятся отдельными задачами) — он делает расхождения НАБЛЮДАЕМЫМИ и
 * необратимыми в молчание:
 * <ul>
 *   <li><b>(а) наше поведение закреплено</b>: результат каждого сценария
 *       (терминальный статус, переменные корневого скоупа, нарушения инварианта
 *       утечек, инциденты) сверяется с ПРИБИТЫМ фактом
 *       ({@code scenario-matrix/flow-expectations.json}). Любое изменение
 *       поведения движка (в т.ч. «поправили» шлюз по совещанию) роняет сборку —
 *       то есть расхождение нельзя «случайно» закрыть без пересъёмки эталона;</li>
 *   <li><b>(б) каждый сценарий измерен против оракула</b>: у каждого сценария
 *       обязан быть прибитый факт живого Zeebe 8.5.0. Нет факта — сборка красная
 *       («пересними эталон»): сценарий, который не сравнивался с оракулом, не
 *       может считаться проверенным;</li>
 *   <li><b>(в) инвариант утечек G-P — в режиме enforce</b>: глобальный
 *       детектор WO-OPS-26 (режим enforce, WO-OPS-32) проверяет КАЖДЫЙ завершённый
 *       инстанс сценария. Утечка = падение сборки; исключение — только
 *       названная находка в allowlist с конкретным WO-id.</li>
 * </ul>
 *
 * <p>Сценарии идут в СВОЕЙ транзакции ({@code REQUIRES_NEW}): сбой одного
 * сценария не отравляет транзакцию следующих.
 *
 * <p>Артефакты прогона: {@code target/scenario-matrix/manifest-flows.json}
 * (схемы — их читает оракул {@code docs/analysis/c8-oracle/scenario-matrix.js})
 * и {@code target/scenario-matrix/ours-flows/*.json} (наши наблюдения).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class FlowScenarioMatrixIntegrationTests {

    /** Каталог артефактов прогона (внутри target/ — сборочный артефакт, не коммитится). */
    public static final Path ARTIFACT_DIR = Path.of("target", "scenario-matrix");
    /** Манифест схем среза F2 (его читает оракул). */
    public static final Path MANIFEST = ARTIFACT_DIR.resolve("manifest-flows.json");
    /** Наши наблюдения по семействам. */
    public static final Path OURS_DIR = ARTIFACT_DIR.resolve("ours-flows");

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

    // ------------------------------------------------------------------
    // Семейства сценариев — по одному тест-методу на семейство, чтобы
    // allowlist инварианта утечек мог адресовать конкретное семейство.
    // ------------------------------------------------------------------

    @Test
    void exclusiveGateway_family_matchesPinnedFacts() throws Exception {
        runFamily("exclusive", List.of(
                "f2-exclusive-condition-a", "f2-exclusive-condition-b", "f2-exclusive-default",
                "f2-exclusive-no-match", "f2-exclusive-multi-match-ab", "f2-exclusive-multi-match-ba"));
    }

    @Test
    void parallelGateway_family_matchesPinnedFacts() throws Exception {
        runFamily("parallel", List.of("f2-parallel-fork-join", "f2-parallel-join-incomplete"));
    }

    @Test
    void inclusiveGateway_family_matchesPinnedFacts() throws Exception {
        runFamily("inclusive", List.of("f2-inclusive-all", "f2-inclusive-partial",
                "f2-inclusive-split-all", "f2-inclusive-split-one", "f2-inclusive-default"));
    }

    @Test
    void eventBasedGateway_family_matchesPinnedFacts() throws Exception {
        runFamily("eventbased", List.of("f2-event-based-message", "f2-event-based-message-race"));
    }

    @Test
    void loopBack_family_matchesPinnedFacts() throws Exception {
        runFamily("loop", List.of("f2-loop-back"));
    }

    /**
     * Прогон семейства: сценарии → наблюдения → сверка с прибитыми фактами.
     *
     * @param family имя семейства (имя файла артефакта + имя семейства в allowlist)
     * @param ids    id сценариев семейства
     */
    private void runFamily(String family, List<String> ids) throws Exception {
        List<BpmnScenario> all = BpmnScenarioGenerator.generateFlows();
        List<BpmnScenario> scenarios = all.stream().filter(s -> ids.contains(s.id())).toList();
        assertThat(scenarios)
                .as("scenarios of family '%s' exist in the generator", family)
                .hasSize(ids.size());

        writeManifest(all);

        // Сначала прогон и артефакты, потом сверка: иначе при отсутствующем
        // эталоне (первый прогон среза) артефакты не были бы записаны и
        // оракул нечего было бы прогонять.
        List<ScenarioOutcome> outcomes = new ArrayList<>();
        for (BpmnScenario scenario : scenarios) {
            outcomes.add(runInOwnTransaction(scenario));
        }
        writeOutcomes(family, outcomes);

        Map<String, Map<String, Object>> pinned = loadPinnedFacts();
        List<String> problems = new ArrayList<>();
        for (ScenarioOutcome outcome : outcomes) {
            Map<String, Object> fact = pinned.get(outcome.scenarioId());
            if (fact == null) {
                problems.add(outcome.scenarioId() + ": нет прибитого факта — пересними эталон "
                        + "(docs/analysis/c8-oracle/scenario-matrix-flow-diff.js)");
                continue;
            }
            if (fact.get("zeebe") == null) {
                problems.add(outcome.scenarioId() + ": нет прибитого факта ЖИВОГО Zeebe 8.5 — "
                        + "пересними эталон (docs/analysis/c8-oracle/scenario-matrix-flow-diff.js)");
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> pinnedOurs = (Map<String, Object>) fact.get("ours");
            if (pinnedOurs == null) {
                problems.add(outcome.scenarioId() + ": прибитый факт без нашей части");
                continue;
            }
            problems.addAll(compareWithPinned(outcome, pinnedOurs));
        }
        assertThat(problems)
                .as("flow-slice divergences from the pinned facts (family=%s)", family)
                .isEmpty();
    }

    /** Сверка наблюдения с прибитой нашей частью факта. */
    private List<String> compareWithPinned(ScenarioOutcome outcome, Map<String, Object> pinnedOurs) {
        List<String> problems = new ArrayList<>();
        String pinnedStatus = String.valueOf(pinnedOurs.get("status"));
        if (!pinnedStatus.equals(outcome.status())) {
            problems.add(outcome.scenarioId() + ": статус изменился: было=" + pinnedStatus
                    + " стало=" + outcome.status());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> pinnedVars = (Map<String, Object>) pinnedOurs.get("vars");
        Map<String, Object> oursVars = new LinkedHashMap<>(outcome.rootVars());
        if (pinnedVars != null && !normalizeVars(pinnedVars).equals(normalizeVars(oursVars))) {
            problems.add(outcome.scenarioId() + ": переменные изменились: было=" + pinnedVars
                    + " стало=" + oursVars);
        }
        @SuppressWarnings("unchecked")
        List<String> pinnedLeaks = (List<String>) pinnedOurs.get("leaks");
        if (pinnedLeaks != null && !normalizeLeaks(pinnedLeaks).equals(normalizeLeaks(outcome.leaks()))) {
            problems.add(outcome.scenarioId() + ": нарушения инварианта утечек изменились: было="
                    + normalizeLeaks(pinnedLeaks) + " стало=" + normalizeLeaks(outcome.leaks()));
        }
        @SuppressWarnings("unchecked")
        List<String> pinnedIncidents = (List<String>) pinnedOurs.get("incidents");
        if (pinnedIncidents != null && !pinnedIncidents.equals(outcome.incidents())) {
            problems.add(outcome.scenarioId() + ": инциденты изменились: было=" + pinnedIncidents
                    + " стало=" + outcome.incidents());
        }
        @SuppressWarnings("unchecked")
        List<String> pinnedPublishes = (List<String>) pinnedOurs.get("publishes");
        if (pinnedPublishes != null && !pinnedPublishes.equals(outcome.publishes())) {
            problems.add(outcome.scenarioId() + ": исходы публикаций изменились: было=" + pinnedPublishes
                    + " стало=" + outcome.publishes());
        }
        return problems;
    }

    /**
     * Нарушения инварианта утечек — без id инстанса.
     *
     * <p>id инстанса случайный (новый инстанс на каждый прогон), поэтому в
     * прибитом факте он не может лежать: иначе КАЖДЫЙ прогон был бы «изменением».
     * Сравнивается СУЩНОСТЬ нарушения (тип + элемент + деталь), а не то, в каком
     * инстансе оно случилось.
     */
    private List<String> normalizeLeaks(List<String> leaks) {
        List<String> out = new ArrayList<>();
        for (String leak : leaks == null ? List.<String>of() : leaks) {
            out.add(leak.replaceAll("instance=[0-9a-f-]{36}\\s*", ""));
        }
        return out;
    }

    /** Приведение карты переменных к сравнимому виду (значения — строки). */
    private Map<String, Object> normalizeVars(Map<String, Object> vars) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : vars.entrySet()) {
            out.put(e.getKey(), e.getValue() == null ? null : String.valueOf(e.getValue()));
        }
        return out;
    }

    /** Прибитые факты (classpath-ресурс, снимается живым прогоном против оракула). */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> loadPinnedFacts() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (java.io.InputStream in = getClass().getResourceAsStream("/scenario-matrix/flow-expectations.json")) {
            assertThat(in).as("scenario-matrix/flow-expectations.json in classpath").isNotNull();
            Map<String, Object> root = mapper.readValue(in, Map.class);
            return (Map<String, Map<String, Object>>) root.get("scenarios");
        }
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

    /** Пишет манифест схем среза F2 (его читает оракул Zeebe). */
    private void writeManifest(List<BpmnScenario> scenarios) throws Exception {
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
            row.put("actions", scenario.actions().stream().map(a -> {
                Map<String, Object> action = new LinkedHashMap<>();
                action.put("kind", a.kind());
                action.put("target", a.target());
                action.put("vars", a.varsJson());
                action.put("correlationKey", a.correlationKey());
                return action;
            }).toList());
            manifest.add(row);
        }
        Files.writeString(MANIFEST,
                mapper.writeValueAsString(Map.of("scenarios", manifest)));
    }

    /** Пишет наши наблюдения по семейству. */
    private void writeOutcomes(String family, List<ScenarioOutcome> outcomes) throws Exception {
        Files.createDirectories(OURS_DIR);
        ObjectMapper mapper = new ObjectMapper();
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
            row.put("publishes", outcome.publishes());
            observed.add(row);
        }
        Files.writeString(OURS_DIR.resolve(family + ".json"),
                mapper.writeValueAsString(Map.of("engine", "zorro", "outcomes", observed)));
    }
}
