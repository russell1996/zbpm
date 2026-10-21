package com.zorrodev.bpm.engine.scenario.event;

import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import com.zorrodev.bpm.engine.scheduler.TimerBatchProcessor;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-OPS-28, ФАЗА 4 («события»): матрица сгенерированных BPMN-сценариев среза
 * «события» против ЖИВОГО Zeebe 8.5.0.
 *
 * <p><b>Что этот тест защищает.</b> Он НЕ чинит расхождения (по WO баги чинятся
 * отдельными задачами) — он делает расхождения НАБЛЮДАЕМЫМИ и необратимыми в молчание:
 * <ul>
 *   <li><b>(а) наше поведение закреплено</b>: наблюдение каждого сценария сверяется с
 *       ПРИБИТЫМ фактом ({@code scenario-matrix/event-expectations.json}). Любое изменение
 *       поведения движка роняет сборку — то есть расхождение нельзя «случайно» закрыть
 *       без пересъёмки эталона;</li>
 *   <li><b>(б) каждый сценарий измерен против оракула</b>: у каждого сценария обязан быть
 *       прибитый факт живого Zeebe 8.5.0. Нет факта — сборка красная («пересними эталон»);
 *       сценарий, который не сравнивался с оракулом, не может считаться проверенным;</li>
 *   <li><b>(в) инвариант утечек G-P — в режиме enforce</b>: глобальный детектор
 *       WO-OPS-26 (режим enforce по умолчанию, WO-OPS-32) проверяет КАЖДЫЙ завершённый
 *       инстанс сценария. Утечка = падение сборки; исключение — только названная находка
 *       в allowlist с конкретным WO-id.</li>
 * </ul>
 *
 * <p>Артефакты прогона: {@code target/scenario-matrix/manifest-events.json} (схемы — их
 * читает оракул {@code docs/analysis/c8-oracle/scenario-matrix-events.js}) и
 * {@code target/scenario-matrix/ours-events/*.json} (наши наблюдения).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class EventScenarioMatrixIntegrationTests {

    /** Каталог артефактов прогона (внутри target/ — сборочный артефакт, не коммитится). */
    public static final Path ARTIFACT_DIR = Path.of("target", "scenario-matrix");
    /** Манифест схем среза F4 (его читает оракул). */
    public static final Path MANIFEST = ARTIFACT_DIR.resolve("manifest-events.json");
    /** Наши наблюдения по семействам. */
    public static final Path OURS_DIR = ARTIFACT_DIR.resolve("ours-events");

    @Autowired
    private ProcessDefinitionService processDefinitionService;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private ActivityService activityService;
    @Autowired
    private DBService dbService;
    @Autowired
    private ActivityRepository activityRepository;
    @Autowired
    private ProcessInstanceRepository processInstanceRepository;
    @Autowired
    private VariableRepository variableRepository;
    @Autowired
    private IncidentRepository incidentRepository;
    @Autowired
    private TimerBatchProcessor timerBatchProcessor;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    // ------------------------------------------------------------------
    // Семейства сценариев — по одному тест-методу на семейство, чтобы
    // allowlist инварианта утечек мог адресовать конкретное семейство.
    // ------------------------------------------------------------------

    @Test
    void start_family_matchesPinnedFacts() throws Exception {
        runFamily("start");
    }

    @Test
    void end_family_matchesPinnedFacts() throws Exception {
        runFamily("end");
    }

    @Test
    void intermediate_family_matchesPinnedFacts() throws Exception {
        runFamily("intermediate");
    }

    @Test
    void boundary_family_matchesPinnedFacts() throws Exception {
        runFamily("boundary");
    }

    @Test
    void timer_family_matchesPinnedFacts() throws Exception {
        runFamily("timer");
    }

    @Test
    void incident_family_matchesPinnedFacts() throws Exception {
        runFamily("incident");
    }

    @Test
    void cancel_family_matchesPinnedFacts() throws Exception {
        runFamily("cancel");
    }

    /**
     * Прогон семейства: сценарии → наблюдения → сверка с прибитыми фактами.
     *
     * @param family имя семейства (имя файла артефакта + семейство в allowlist)
     */
    private void runFamily(String family) throws Exception {
        List<EventScenario> all = EventScenarioGenerator.generate();
        List<EventScenario> scenarios = all.stream().filter(s -> family.equals(s.family())).toList();
        assertThat(scenarios)
                .as("scenarios of family '%s' exist in the generator", family)
                .isNotEmpty();

        writeManifest(all);

        // Сначала прогон и артефакты, потом сверка: иначе при отсутствующем
        // эталоне (первый прогон среза) артефакты не были бы записаны и
        // оракулу нечего было бы прогонять.
        List<EventScenarioOutcome> outcomes = new ArrayList<>();
        for (EventScenario scenario : scenarios) {
            outcomes.add(newRunner().run(scenario));
        }
        writeOutcomes(family, outcomes, scenarios);

        Map<String, Map<String, Object>> pinned = loadPinnedFacts();
        List<String> problems = new ArrayList<>();
        for (EventScenarioOutcome outcome : outcomes) {
            Map<String, Object> fact = pinned.get(outcome.scenarioId());
            if (fact == null) {
                problems.add(outcome.scenarioId() + ": нет прибитого факта — пересними эталон "
                        + "(docs/analysis/c8-oracle/scenario-matrix-event-diff.js)");
                continue;
            }
            if (fact.get("zeebe") == null) {
                problems.add(outcome.scenarioId() + ": нет прибитого факта ЖИВОГО Zeebe 8.5 — "
                        + "пересними эталон (docs/analysis/c8-oracle/scenario-matrix-event-diff.js)");
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
                .as("event-slice divergences from the pinned facts (family=%s)", family)
                .isEmpty();
    }

    /** Сверка наблюдения с прибитой нашей частью факта. */
    private List<String> compareWithPinned(EventScenarioOutcome outcome, Map<String, Object> pinnedOurs) {
        List<String> problems = new ArrayList<>();
        // Текущее наблюдение в ТОЙ ЖЕ форме, что и прибитое (diff-инструмент пишет
        // status/leaks/incidents рядом с картой наблюдения).
        Map<String, Object> current = new LinkedHashMap<>(outcome.observation());
        current.put("status", outcome.status());
        current.put("leaks", outcome.leaks());
        current.put("incidents", outcome.incidents());
        for (Map.Entry<String, Object> entry : pinnedOurs.entrySet()) {
            String key = entry.getKey();
            Object expected = entry.getValue();
            Object actual = current.get(key);
            if (!sameValue(expected, actual)) {
                problems.add(outcome.scenarioId() + ": наблюдение '" + key + "' изменилось: было="
                        + expected + " стало=" + actual);
            }
        }
        List<String> pinnedLeaks = stringList(pinnedOurs.get("leaks"));
        if (!normalizeLeaks(pinnedLeaks).equals(normalizeLeaks(outcome.leaks()))) {
            problems.add(outcome.scenarioId() + ": нарушения инварианта утечек изменились: было="
                    + normalizeLeaks(pinnedLeaks) + " стало=" + normalizeLeaks(outcome.leaks()));
        }
        List<String> pinnedIncidents = stringList(pinnedOurs.get("incidents"));
        if (!pinnedIncidents.equals(outcome.incidents())) {
            problems.add(outcome.scenarioId() + ": инциденты изменились: было=" + pinnedIncidents
                    + " стало=" + outcome.incidents());
        }
        return problems;
    }

    /**
     * Приводит значение к сравнимому виду: JSON-СТРОКА разбирается в структуру.
     *
     * <p>Наша сторона хранит значение переменной как ТЕКСТ ({@code ["a","b"]}), а
     * прибитый факт (его пишет diff-инструмент по данным оракула) — как разобранная
     * структура. Это ОДНО И ТО ЖЕ значение в разной форме хранения; без приведения
     * сравнение давало бы ложное «изменилось».
     */
    private Object parseIfJson(Object raw) {
        if (!(raw instanceof String s)) {
            return raw;
        }
        String t = s.trim();
        if (t.startsWith("[") || t.startsWith("{")) {
            try {
                return new tools.jackson.databind.ObjectMapper().readValue(t, Object.class);
            } catch (RuntimeException e) {
                return raw;
            }
        }
        return raw;
    }

    /** Сравнение значений: карты — по ключам, остальное — по строке. */
    private boolean sameValue(Object expected, Object actual) {
        expected = parseIfJson(expected);
        actual = parseIfJson(actual);
        if (expected instanceof Map<?, ?> expectedMap && actual instanceof Map<?, ?> actualMap) {
            if (!expectedMap.keySet().equals(actualMap.keySet())) {
                return false;
            }
            for (Map.Entry<?, ?> e : expectedMap.entrySet()) {
                if (!sameValue(e.getValue(), actualMap.get(e.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (expected instanceof List<?> expectedList && actual instanceof List<?> actualList) {
            return expectedList.size() == actualList.size()
                    && java.util.stream.IntStream.range(0, expectedList.size())
                            .allMatch(i -> sameValue(expectedList.get(i), actualList.get(i)));
        }
        return String.valueOf(expected).equals(String.valueOf(actual));
    }

    /**
     * Нарушения инварианта утечек — без id инстанса.
     *
     * <p>id инстанса случайный (новый инстанс на каждый прогон), поэтому в прибитом
     * факте он не может лежать: иначе КАЖДЫЙ прогон был бы «изменением».
     */
    private List<String> normalizeLeaks(List<String> leaks) {
        List<String> out = new ArrayList<>();
        for (String leak : leaks == null ? List.<String>of() : leaks) {
            out.add(leak.replaceAll("instance=[0-9a-f-]{36}\\s*", ""));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object raw) {
        return raw instanceof List<?> list ? (List<String>) list : List.of();
    }

    /** Прибитые факты (classpath-ресурс, снимается живым прогоном против оракула). */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> loadPinnedFacts() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (java.io.InputStream in = getClass().getResourceAsStream("/scenario-matrix/event-expectations.json")) {
            assertThat(in).as("scenario-matrix/event-expectations.json in classpath").isNotNull();
            Map<String, Object> root = mapper.readValue(in, Map.class);
            return (Map<String, Map<String, Object>>) root.get("scenarios");
        }
    }

    /** Раннер сценария (все бины движка). */
    private EventScenarioRunner newRunner() {
        return new EventScenarioRunner(processDefinitionService, runtimeService, activityService,
                dbService, activityRepository, processInstanceRepository, variableRepository,
                incidentRepository, timerBatchProcessor, jdbcTemplate, transactionManager);
    }

    /** Пишет манифест схем среза F4 (его читает оракул Zeebe). */
    private void writeManifest(List<EventScenario> scenarios) throws Exception {
        Files.createDirectories(ARTIFACT_DIR);
        ObjectMapper mapper = new ObjectMapper();
        List<Map<String, Object>> manifest = new ArrayList<>();
        for (EventScenario scenario : scenarios) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", scenario.id());
            row.put("family", scenario.family());
            row.put("element", scenario.element());
            row.put("bpmn", scenario.bpmn());
            row.put("vars", scenario.varsJson());
            row.put("actions", scenario.actions().stream().map(a -> {
                Map<String, Object> action = new LinkedHashMap<>();
                action.put("kind", a.kind());
                action.put("target", a.target());
                action.put("vars", a.varsJson());
                action.put("ms", a.ms());
                action.put("code", a.code());
                return action;
            }).toList());
            row.put("startMode", scenario.startMode().name());
            row.put("startSignal", scenario.startSignal());
            row.put("dumpElementId", scenario.dumpElementId());
            row.put("dumpJobType", scenario.dumpJobType());
            row.put("jobTypes", scenario.jobTypes());
            row.put("waitMs", scenario.waitMs());
            row.put("volatileKeys", scenario.volatileKeys());
            row.put("elementTypes", scenario.elementTypes());
            row.put("note", scenario.note());
            manifest.add(row);
        }
        Files.writeString(MANIFEST,
                mapper.writeValueAsString(Map.of("scenarios", manifest)));
    }

    /** Пишет наши наблюдения по семейству. */
    private void writeOutcomes(String family, List<EventScenarioOutcome> outcomes,
            List<EventScenario> scenarios) throws Exception {
        Files.createDirectories(OURS_DIR);
        ObjectMapper mapper = new ObjectMapper();
        List<Map<String, Object>> observed = new ArrayList<>();
        for (EventScenarioOutcome outcome : outcomes) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", outcome.scenarioId());
            row.put("family", outcome.family());
            row.put("element", outcome.element());
            row.put("status", outcome.status());
            row.put("observation", outcome.observation());
            row.put("leaks", outcome.leaks());
            row.put("incidents", outcome.incidents());
            row.put("error", outcome.error());
            row.put("volatileKeys", scenarios.stream()
                    .filter(sc -> sc.id().equals(outcome.scenarioId()))
                    .findFirst()
                    .map(EventScenario::volatileKeys)
                    .orElse(List.of()));
            observed.add(row);
        }
        Files.writeString(OURS_DIR.resolve(family + ".json"),
                mapper.writeValueAsString(Map.of("engine", "zorro", "outcomes", observed)));
    }
}
