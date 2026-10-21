package com.zorrodev.bpm.engine.scenario;

import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-OPS-28, ФАЗА 5 («сквозные»): рестарт движка посреди процесса, параллельные
 * завершения (гонки {@code lockInstanceFirst}) и миграция версии определения для
 * живых инстансов.
 *
 * <p><b>Что этот тест защищает.</b> Он НЕ чинит расхождения (по кампании баги
 * чинятся отдельными задачами) — он делает их НАБЛЮДАЕМЫМИ и необратимыми в
 * молчание:
 * <ul>
 *   <li>наше поведение закреплено: каждое наблюдение сверяется с ПРИБИТЫМ фактом
 *       ({@code scenario-matrix/cross-expectations.json}); изменение поведения
 *       роняет сборку — то есть расхождение нельзя закрыть «случайно»;</li>
 *   <li>инвариант утечек G-P — в режиме enforce (глобальный детектор
 *       WO-OPS-26/32): утечка = падение сборки; исключение — только с названной
 *       находкой в allowlist;</li>
 *   <li>сценарии идут в СВОЕЙ транзакции: сбой одного не отравляет остальные.</li>
 * </ul>
 *
 * <p>Артефакты прогона: {@code target/scenario-matrix/manifest-cross.json} (схемы —
 * их читает оракул {@code docs/analysis/c8-oracle/scenario-matrix-cross.js}) и
 * {@code target/scenario-matrix/ours-cross-h2.json} (наши наблюдения).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class CrossCuttingScenarioIntegrationTests {

    /** Каталог артефактов прогона (внутри target/ — сборочный артефакт, не коммитится). */
    public static final Path ARTIFACT_DIR = Path.of("target", "scenario-matrix");
    /** Манифест схем среза F5 (его читает оракул). */
    public static final Path MANIFEST = ARTIFACT_DIR.resolve("manifest-cross.json");
    /** Наши наблюдения (H2). */
    public static final Path OURS_H2 = ARTIFACT_DIR.resolve("ours-cross-h2.json");
    /** Прибитые факты среза F5. */
    public static final String PINNED = "/scenario-matrix/cross-expectations.json";

    /** Сколько инстансов гоняем в оси параллельных завершений. */
    private static final int RACE_ITERATIONS = 25;

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
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void writeManifest() throws Exception {
        Files.createDirectories(ARTIFACT_DIR);
        ObjectMapper mapper = new ObjectMapper();
        List<Map<String, Object>> manifest = new ArrayList<>();
        for (BpmnScenario scenario : BpmnScenarioGenerator.generateCrossCutting()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", scenario.id());
            row.put("container", scenario.container());
            row.put("content", scenario.content());
            row.put("bpmn", scenario.bpmn());
            row.put("vars", scenario.varsJson());
            row.put("taskElementIds", scenario.taskElementIds());
            row.put("dumpElementId", scenario.dumpElementId());
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
            // Оракулу нужно знать ПАРНУЮ версию схемы (деплой v2 при живом v1) и
            // признак «завершать одновременно» (ось параллельных завершений).
            row.put("deployAfterStart", BpmnScenarioGenerator.pairedVersionBpmn(scenario.id()));
            row.put("parallelActions", BpmnScenarioGenerator.parallelActions(scenario));
            manifest.add(row);
        }
        Files.writeString(MANIFEST, mapper.writeValueAsString(Map.of("scenarios", manifest)));
    }

    // ------------------------------------------------------------------
    // Оси среза F5 — по одному тест-методу на ось, чтобы allowlist
    // инварианта утечек мог адресовать конкретную ось.
    // ------------------------------------------------------------------

    @Test
    void restartMidProcess_engineRestartKeepsTheProcessRunning() throws Exception {
        BpmnScenario scenario = scenario("f5-restart-midprocess");
        CrossCuttingScenarioSupport.Observation observation =
                support().restartMidProcess(scenario, restartedEngineFactory());

        assertThat(observation.extra())
                .as("the restarted engine must see the live task (state lives in the DB, not in RAM)")
                .containsEntry("midTaskCreatedBeforeRestart", true)
                .containsEntry("restartedEngineSawTask", true)
                .containsEntry("continuedAfterRestart", true);
        assertThat(observation.status()).as("the process must finish after the restart").isEqualTo("COMPLETED");
        assertThat(observation.error()).isEmpty();
        assertThat(observation.leaks()).as("G-P: no residue after the restart").isEmpty();
        assertThat(observation.incidents()).isEmpty();
        assertThat(observation.vars()).containsEntry("phase", "after");

        writeObservation("f5-restart-midprocess", observation);
        assertPinned("f5-restart-midprocess", observation);
    }

    @Test
    void parallelCompletions_concurrentBranchesJoinExactlyOnce() {
        BpmnScenario scenario = scenario("f5-parallel-race");
        CrossCuttingScenarioSupport.Observation observation =
                support().parallelRace(scenario, RACE_ITERATIONS);

        assertThat(observation.extra())
                .as("every iteration must complete the instance")
                .containsEntry("iterations", RACE_ITERATIONS)
                .containsEntry("instancesCompleted", RACE_ITERATIONS);
        assertThat(observation.extra().get("errors")).as("no race errors").isEqualTo(List.of());
        assertThat(observation.extra())
                .as("the join must produce exactly ONE observation task per instance")
                .containsEntry("dumpActivityRowsMax", 1);
        assertThat(observation.status()).isEqualTo("COMPLETED");
        assertThat(observation.vars())
                .as("every branch of the fork must leave its variable")
                .containsEntry("stA", "stA")
                .containsEntry("stB", "stB")
                .containsEntry("stC", "stC")
                .containsEntry("stD", "stD");
        assertThat(observation.leaks()).as("G-P: no residue after the race").isEmpty();
        assertThat(observation.incidents()).isEmpty();

        writeObservation("f5-parallel-race", observation);
        assertPinned("f5-parallel-race", observation);
    }

    @Test
    void definitionVersionMigration_liveInstanceKeepsItsDeployedVersion() {
        BpmnScenario v1 = scenario("f5-version-v1");
        BpmnScenario v2 = scenario("f5-version-v2");
        CrossCuttingScenarioSupport.Observation observation = support().versionMigration(v1, v2);

        assertThat(observation.extra())
                .as("the live instance must continue on the version it started on")
                .containsEntry("continuedWith", "stV1")
                .containsEntry("instanceVersionEqualsDeployedV1", true)
                .containsEntry("deployedVersionDelta", 1);
        assertThat(observation.status()).isEqualTo("COMPLETED");
        assertThat(observation.leaks()).as("G-P: no residue after the version migration").isEmpty();
        assertThat(observation.incidents()).isEmpty();

        writeObservation("f5-version-v1", observation);
        assertPinned("f5-version-v1", observation);
    }

    @Test
    void definitionVersionMigration_controlInstanceOnTheNewVersionContinuesOnIt() {
        BpmnScenario v2 = scenario("f5-version-v2");
        CrossCuttingScenarioSupport.Observation observation = support().versionControl(v2);

        assertThat(observation.extra())
                .as("an instance started on the NEW version continues on it")
                .containsEntry("continuedWith", "stV2")
                .containsEntry("instanceVersionEqualsDeployed", true);
        assertThat(observation.status()).isEqualTo("COMPLETED");
        assertThat(observation.leaks()).as("G-P: no residue in the control run").isEmpty();
        assertThat(observation.incidents()).isEmpty();

        writeObservation("f5-version-v2", observation);
        assertPinned("f5-version-v2", observation);
    }

    // ------------------------------------------------------------------
    // Помощники
    // ------------------------------------------------------------------

    private CrossCuttingScenarioSupport support() {
        return new CrossCuttingScenarioSupport(processDefinitionService, runtimeService, queryService,
                activityRepository, variableRepository, incidentRepository, jdbcTemplate);
    }

    private static BpmnScenario scenario(String id) {
        BpmnScenario found = BpmnScenarioGenerator.generateCrossCutting().stream()
                .filter(s -> id.equals(s.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no F5 scenario with id " + id));
        return found;
    }

    /**
     * «Перезапущенный» движок: ВТОРОЙ Spring-контекст на той же БД.
     *
     * <p>Всё, что нужно для продолжения процесса, — в БД (activity, переменные,
     * файл BPMN на диске); в памяти первого движка не остаётся ничего, что было
     * бы нужно «пережить» рестарту. Если бы состояние жило в памяти, свежий
     * движок не увидел бы живую задачу.
     */
    private CrossCuttingScenarioSupport.RestartedEngineFactory restartedEngineFactory() {
        return () -> {
            ConfigurableApplicationContext ctx = new SpringApplicationBuilder(TestMain.class)
                    .properties(
                            "spring.profiles.active=test",
                            "spring.main.web-application-type=none",
                            "app.filesDir=target/files",
                            "zorrobpm.engine.timer-poll-interval-ms=3600000",
                            "spring.rabbitmq.listener.simple.auto-startup=false")
                    .run();
            return new CrossCuttingScenarioSupport.RestartedEngine() {
                @Override
                public RuntimeService runtimeService() {
                    return ctx.getBean(RuntimeService.class);
                }

                @Override
                public ActivityRepository activityRepository() {
                    return ctx.getBean(ActivityRepository.class);
                }

                @Override
                public void close() {
                    ctx.close();
                }
            };
        };
    }

    /** Пишет наше наблюдение в артефакт прогона (для дифференциала). */
    private void writeObservation(String id, CrossCuttingScenarioSupport.Observation observation) {
        try {
            Files.createDirectories(ARTIFACT_DIR);
            ObjectMapper mapper = new ObjectMapper();
            List<Map<String, Object>> rows = new ArrayList<>();
            if (Files.exists(OURS_H2)) {
                Map<String, Object> existing = mapper.readValue(Files.readString(OURS_H2), Map.class);
                Object outcomes = existing.get("outcomes");
                if (outcomes instanceof List<?> list) {
                    for (Object row : list) {
                        if (row instanceof Map<?, ?> map && id.equals(map.get("scenarioId"))) {
                            continue;
                        }
                        @SuppressWarnings("unchecked")
                        Map<String, Object> typed = (Map<String, Object>) row;
                        rows.add(typed);
                    }
                }
            }
            rows.add(observation.toMap());
            Files.writeString(OURS_H2, mapper.writeValueAsString(Map.of("engine", "zorro", "db", "h2", "outcomes", rows)));
        } catch (Exception e) {
            throw new IllegalStateException("cannot write the observation artifact", e);
        }
    }

    /**
     * Сверка наблюдения с ПРИБИТЫМ фактом (наша часть).
     *
     * <p>Прибитый факт — не «красивая табличка», а стоп: любое изменение
     * поведения движка роняет сборку, пока эталон не переприбит осознанно.
     */
    @SuppressWarnings("unchecked")
    private void assertPinned(String id, CrossCuttingScenarioSupport.Observation observation) {
        Map<String, Map<String, Object>> pinned = loadPinnedFacts();
        Map<String, Object> fact = pinned.get(id);
        assertThat(fact).as("pinned fact for %s exists", id).isNotNull();
        Map<String, Object> pinnedOurs = (Map<String, Object>) fact.get("ours");
        assertThat(pinnedOurs).as("pinned fact for %s has our part", id).isNotNull();

        List<String> problems = new ArrayList<>();
        if (!String.valueOf(pinnedOurs.get("status")).equals(observation.status())) {
            problems.add(id + ": статус изменился: было=" + pinnedOurs.get("status")
                    + " стало=" + observation.status());
        }
        Map<String, Object> pinnedVars = (Map<String, Object>) pinnedOurs.get("vars");
        if (pinnedVars != null && !normalizeVars(pinnedVars).equals(normalizeVars(observation.vars()))) {
            problems.add(id + ": переменные изменились: было=" + pinnedVars + " стало=" + observation.vars());
        }
        List<String> pinnedLeaks = (List<String>) pinnedOurs.get("leaks");
        if (pinnedLeaks != null && !normalizeLeaks(pinnedLeaks).equals(normalizeLeaks(observation.leaks()))) {
            problems.add(id + ": нарушения инварианта утечек изменились: было=" + normalizeLeaks(pinnedLeaks)
                    + " стало=" + normalizeLeaks(observation.leaks()));
        }
        List<String> pinnedIncidents = (List<String>) pinnedOurs.get("incidents");
        if (pinnedIncidents != null && !pinnedIncidents.equals(observation.incidents())) {
            problems.add(id + ": инциденты изменились: было=" + pinnedIncidents + " стало=" + observation.incidents());
        }
        Map<String, Object> pinnedExtra = (Map<String, Object>) pinnedOurs.get("extra");
        if (pinnedExtra != null && !normalizeVars(pinnedExtra).equals(normalizeVars(observation.extra()))) {
            problems.add(id + ": axis-факты изменились: было=" + pinnedExtra + " стало=" + observation.extra());
        }
        assertThat(problems).as("cross-cutting slice divergences from the pinned facts (%s)", id).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> loadPinnedFacts() {
        ObjectMapper mapper = new ObjectMapper();
        try (java.io.InputStream in = getClass().getResourceAsStream(PINNED)) {
            assertThat(in).as("scenario-matrix/cross-expectations.json in classpath").isNotNull();
            Map<String, Object> root = mapper.readValue(in, Map.class);
            return (Map<String, Map<String, Object>>) root.get("scenarios");
        } catch (Exception e) {
            throw new IllegalStateException("cannot read the pinned facts", e);
        }
    }

    /** Нарушения инварианта утечек — без id инстанса (id случайный). */
    private List<String> normalizeLeaks(List<String> leaks) {
        List<String> out = new ArrayList<>();
        for (String leak : leaks == null ? List.<String>of() : leaks) {
            out.add(leak.replaceAll("instance=[0-9a-f-]{36}\\s*", ""));
        }
        return out;
    }

    /** Приведение карты к сравнимому виду (значения — строки). */
    private Map<String, Object> normalizeVars(Map<String, ?> vars) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, ?> e : vars.entrySet()) {
            out.put(e.getKey(), e.getValue() == null ? null : String.valueOf(e.getValue()));
        }
        return out;
    }
}
