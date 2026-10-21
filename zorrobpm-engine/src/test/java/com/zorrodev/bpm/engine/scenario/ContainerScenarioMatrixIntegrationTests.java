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
 * WO-OPS-28 ФАЗА 3: срез «контейнеры» (F3) — прогон на нашем движке.
 *
 * <p>Что проверяется:
 * <ul>
 *   <li><b>(а) инвариант утечек</b> (WO-OPS-26): после завершения инстанса не
 *       остаётся живых activity/задач/таймеров/подписок/arrival-строк;</li>
 *   <li><b>(б) сверка с прибитыми фактами</b> {@code scenario-matrix/container-expectations.json}:
 *       в файле лежат ОБЕ стороны — что сделал ЖИВОЙ Zeebe 8.5.0 и что сделали мы,
 *       плюс вычисленный вердикт. Расхождение НЕ чинится в кампании (баги —
 *       отдельными задачами), но оно не должно исчезнуть молча: изменение
 *       нашего поведения роняет сборку и заставляет пересмотреть расхождение;</li>
 *   <li><b>(в) терминальное состояние</b>: инстанс дошёл до завершения, инцидентов нет
 *       (инцидент — парковка потока, а не поведение).</li>
 * </ul>
 *
 * <p>Каждый сценарий идёт в СВОЕЙ транзакции ({@code REQUIRES_NEW}): падение одного
 * сценария не отравляет транзакцию следующих.
 *
 * <p>Артефакты прогона (для оракула и для отчёта):
 * {@code target/scenario-matrix/manifest-containers.json} (схемы) и
 * {@code target/scenario-matrix/ours-containers/} (наши наблюдения по семействам).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class ContainerScenarioMatrixIntegrationTests {

    /** Каталог артефактов прогона (внутри target/ — сборочный артефакт, не коммитится). */
    public static final Path ARTIFACT_DIR = Path.of("target", "scenario-matrix");
    /** Манифест схем среза F3 (его читает оракул Zeebe). */
    public static final Path MANIFEST = ARTIFACT_DIR.resolve("manifest-containers.json");
    /** Наши наблюдения по семействам. */
    public static final Path OURS_DIR = ARTIFACT_DIR.resolve("ours-containers");

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
    void subprocess_family_matchesPinnedFacts() throws Exception {
        runFamily("subprocess", List.of(
                "f3-subprocess-plain", "f3-subprocess-plain-vars",
                "f3-subprocess-nested", "f3-subprocess-nested-vars"));
    }

    @Test
    void miSubProcess_family_matchesPinnedFacts() throws Exception {
        runFamily("misubprocess", List.of(
                "f3-mi-subprocess-parallel", "f3-mi-subprocess-sequential",
                "f3-mi-subprocess-completion-condition", "f3-mi-task-completion-condition"));
    }

    @Test
    void eventSubProcess_family_matchesPinnedFacts() throws Exception {
        runFamily("eventsubprocess", List.of(
                "f3-event-subprocess-interrupting", "f3-event-subprocess-non-interrupting"));
    }

    @Test
    void callActivity_family_matchesPinnedFacts() throws Exception {
        runFamily("callactivity", List.of(
                "f3-call-activity", "f3-call-activity-vars", "f3-call-activity-mi"));
    }

    @Test
    void transaction_family_matchesPinnedFacts() throws Exception {
        runFamily("transaction", List.of("f3-transaction-plain", "f3-transaction-cancel"));
    }

    /**
     * Прогон семейства: сценарии → наблюдения → сверка с прибитыми фактами.
     *
     * @param family имя семейства (имя файла артефакта + имя семейства в allowlist)
     * @param ids    id сценариев семейства
     */
    private void runFamily(String family, List<String> ids) throws Exception {
        List<ContainerScenario> all = ContainerScenarioGenerator.generate();
        List<ContainerScenario> scenarios = all.stream().filter(s -> ids.contains(s.id())).toList();
        assertThat(scenarios)
                .as("scenarios of family '%s' exist in the generator", family)
                .hasSize(ids.size());

        writeManifest(all);

        // Сначала прогон и артефакты, потом сверка: иначе при отсутствующем
        // эталоне (первый прогон среза) артефакты не были бы записаны и
        // оракулу нечего было бы прогонять.
        List<ScenarioOutcome> outcomes = new ArrayList<>();
        for (ContainerScenario scenario : scenarios) {
            outcomes.add(runInOwnTransaction(scenario));
        }
        writeOutcomes(family, outcomes);

        Map<String, Map<String, Object>> pinned = loadPinnedFacts();
        List<String> problems = new ArrayList<>();
        for (ScenarioOutcome outcome : outcomes) {
            Map<String, Object> fact = pinned.get(outcome.scenarioId());
            if (fact == null) {
                problems.add(outcome.scenarioId() + ": нет прибитого факта — пересними эталон "
                        + "(docs/analysis/c8-oracle/scenario-matrix-container-diff.js)");
                continue;
            }
            if (fact.get("zeebe") == null) {
                problems.add(outcome.scenarioId() + ": нет прибитого факта ЖИВОГО Zeebe 8.5 — "
                        + "пересними эталон (docs/analysis/c8-oracle/scenario-matrix-container-diff.js)");
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
                .as("container-slice divergences from the pinned facts (family=%s)", family)
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
        return problems;
    }

    /** Нарушения инварианта — без id инстанса (он случайный). */
    private List<String> normalizeLeaks(List<String> leaks) {
        return leaks.stream().map(l -> l.replaceAll("instance=[0-9a-f-]{36}\\s*", "")).sorted().toList();
    }

    /** Переменные — как разобранный JSON (значение «true» и строка «true» — одно и то же). */
    private Map<String, Object> normalizeVars(Map<String, Object> vars) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : vars.keySet().stream().sorted().toList()) {
            out.put(key, normalize(vars.get(key)));
        }
        return out;
    }

    private Object normalize(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = String.valueOf(raw).trim();
        if (text.startsWith("[") || text.startsWith("{")) {
            try {
                return new ObjectMapper().readValue(text, Object.class);
            } catch (Exception e) {
                return raw;
            }
        }
        return raw;
    }

    /** Прибитые факты (classpath-ресурс, снимается живым прогоном). */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> loadPinnedFacts() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (java.io.InputStream in = getClass().getResourceAsStream("/scenario-matrix/container-expectations.json")) {
            assertThat(in).as("scenario-matrix/container-expectations.json in classpath").isNotNull();
            Map<String, Object> root = mapper.readValue(in, Map.class);
            return (Map<String, Map<String, Object>>) root.get("scenarios");
        }
    }

    /** Каждый сценарий — в своей транзакции (сбой одного не отравляет остальные). */
    private ScenarioOutcome runInOwnTransaction(ContainerScenario scenario) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        try {
            return template.execute(status -> {
                ContainerScenarioRunner runner = new ContainerScenarioRunner(processDefinitionService,
                        runtimeService, queryService, activityRepository, variableRepository,
                        incidentRepository, elementSupport, jdbcTemplate);
                return runner.run(scenario);
            });
        } catch (RuntimeException e) {
            return new ScenarioOutcome(scenario.id(), scenario.base().container(), scenario.content(),
                    scenario.cardinality(), "", "FAILED", Map.of(), List.of(), List.of(),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Пишет манифест схем среза F3 — его читает оракул Zeebe. */
    private void writeManifest(List<ContainerScenario> scenarios) throws Exception {
        Files.createDirectories(ARTIFACT_DIR);
        ObjectMapper mapper = new ObjectMapper();
        List<Map<String, Object>> manifest = new ArrayList<>();
        for (ContainerScenario scenario : scenarios) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", scenario.id());
            row.put("container", scenario.base().container());
            row.put("content", scenario.content());
            row.put("cardinality", scenario.cardinality());
            row.put("items", scenario.items());
            row.put("bpmn", scenario.bpmn());
            row.put("childBpmn", scenario.childBpmn());
            row.put("vars", scenario.varsJson());
            row.put("taskElementIds", scenario.taskElementIds());
            row.put("dumpElementId", scenario.dumpElementId());
            row.put("dumpJobType", scenario.dumpJobType());
            row.put("expectation", scenario.expectation());
            List<Map<String, Object>> steps = new ArrayList<>();
            for (ContainerScenario.Step step : scenario.steps()) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("elementId", step.elementId());
                s.put("jobType", step.jobType());
                s.put("vars", step.varsJson());
                s.put("item", step.item());
                steps.add(s);
            }
            row.put("steps", steps);
            manifest.add(row);
        }
        Files.writeString(MANIFEST, mapper.writeValueAsString(Map.of("scenarios", manifest)));
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
            row.put("status", outcome.status());
            row.put("rootVars", outcome.rootVars());
            row.put("leaks", outcome.leaks());
            row.put("incidents", outcome.incidents());
            row.put("error", outcome.error());
            observed.add(row);
        }
        Files.writeString(OURS_DIR.resolve(family + ".json"),
                mapper.writeValueAsString(Map.of("engine", "zorro", "outcomes", observed)));
    }
}
