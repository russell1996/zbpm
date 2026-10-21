package com.zorrodev.bpm.engine.scenario;

import com.zorrodev.bpm.engine.PostgresIT;
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
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-OPS-28, ФАЗА 5, ось «PG vs H2»: ТЕ ЖЕ «сквозные» сценарии — на реальном
 * PostgreSQL 16.
 *
 * <p>Зачем: движок в тестах кампании живёт на H2, а прод — на PostgreSQL. Сценарии
 * «сквозные» (рестарт, гонка параллельных завершений, миграция версии) — те, где
 * различие СУБД проявляется сильнее всего: блокировки строк, семантика транзакций
 * и изоляция. Каждое наблюдение сверяется с ОТДЕЛЬНОЙ прибитой частью
 * ({@code scenario-matrix/cross-expectations.json} → {@code pg}), а не с H2-частью:
 * тогда расхождение PG↔H2 видно как расхождение, а не как «тест поехал».
 *
 * <p>Сверка «одинаково ли на H2 и на PG» — в отчёте фазы и в
 * {@code phase5-divergences.md} (сравнение прибитых частей {@code ours} и {@code pg}).
 *
 * <p>Прогон: {@code bash ci/run-pg-tests.sh} (джоб {@code test:pg}) — он поднимает
 * свой PostgreSQL 16 и гоняет все {@code @Tag("pg")}-тесты движка.
 */
class CrossCuttingScenarioPgIT extends PostgresIT {

    /** Артефакты прогона PG. */
    public static final Path ARTIFACT_DIR = Path.of("target", "scenario-matrix");
    /** Наши наблюдения (PG). */
    public static final Path OURS_PG = ARTIFACT_DIR.resolve("ours-cross-pg.json");
    /** Прибитые факты среза F5. */
    public static final String PINNED = "/scenario-matrix/cross-expectations.json";

    /** Сколько инстансов гоняем в оси параллельных завершений (как на H2). */
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
    static void prepare() throws Exception {
        Files.createDirectories(ARTIFACT_DIR);
    }

    @Test
    void restartMidProcess_onPostgres_theProcessSurvivesTheEngineRestart() throws Exception {
        BpmnScenario scenario = scenario("f5-restart-midprocess");
        CrossCuttingScenarioSupport.Observation observation =
                support().restartMidProcess(scenario, restartedEngineFactory());

        assertThat(observation.extra())
                .containsEntry("midTaskCreatedBeforeRestart", true)
                .containsEntry("restartedEngineSawTask", true)
                .containsEntry("continuedAfterRestart", true);
        assertThat(observation.status()).isEqualTo("COMPLETED");
        assertThat(observation.leaks()).isEmpty();
        assertThat(observation.incidents()).isEmpty();

        writeObservation("f5-restart-midprocess", observation);
        assertPinned("f5-restart-midprocess", observation);
    }

    @Test
    void parallelCompletions_onPostgres_theJoinFiresExactlyOnce() {
        BpmnScenario scenario = scenario("f5-parallel-race");
        CrossCuttingScenarioSupport.Observation observation =
                support().parallelRace(scenario, RACE_ITERATIONS);

        assertThat(observation.extra())
                .containsEntry("iterations", RACE_ITERATIONS)
                .containsEntry("instancesCompleted", RACE_ITERATIONS)
                .containsEntry("dumpActivityRowsMax", 1);
        assertThat(observation.extra().get("errors")).isEqualTo(List.of());
        assertThat(observation.status()).isEqualTo("COMPLETED");
        assertThat(observation.leaks()).isEmpty();
        assertThat(observation.incidents()).isEmpty();

        writeObservation("f5-parallel-race", observation);
        assertPinned("f5-parallel-race", observation);
    }

    @Test
    void definitionVersionMigration_onPostgres_theLiveInstanceKeepsItsVersion() {
        BpmnScenario v1 = scenario("f5-version-v1");
        BpmnScenario v2 = scenario("f5-version-v2");
        CrossCuttingScenarioSupport.Observation observation = support().versionMigration(v1, v2);

        assertThat(observation.extra())
                .containsEntry("continuedWith", "stV1")
                .containsEntry("instanceVersionEqualsDeployedV1", true)
                .containsEntry("deployedVersionDelta", 1);
        assertThat(observation.status()).isEqualTo("COMPLETED");
        assertThat(observation.leaks()).isEmpty();
        assertThat(observation.incidents()).isEmpty();

        writeObservation("f5-version-v1", observation);
        assertPinned("f5-version-v1", observation);
    }

    @Test
    void definitionVersionMigration_onPostgres_controlInstanceOnTheNewVersionContinuesOnIt() {
        BpmnScenario v2 = scenario("f5-version-v2");
        CrossCuttingScenarioSupport.Observation observation = support().versionControl(v2);

        assertThat(observation.extra())
                .containsEntry("continuedWith", "stV2")
                .containsEntry("instanceVersionEqualsDeployed", true);
        assertThat(observation.status()).isEqualTo("COMPLETED");
        assertThat(observation.leaks()).isEmpty();
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
     * «Перезапущенный» движок на PostgreSQL: ВТОРОЙ Spring-контекст на ТОЙ ЖЕ базе.
     *
     * <p>Свойства datasource задаются АРГУМЕНТАМИ запуска (command line args), а не
     * {@code properties(...)}: {@code properties()} — это DEFAULT-properties, и
     * {@code application.properties} из тестовых ресурсов (в нём H2) перебил бы их
     * по приоритету. Аргументы запуска — выше по приоритету, чем файл профиля.
     */
    private CrossCuttingScenarioSupport.RestartedEngineFactory restartedEngineFactory() {
        return () -> {
            ConfigurableApplicationContext ctx = new SpringApplicationBuilder(TestMain.class)
                    .run(
                            "--spring.datasource.url=" + jdbcUrl(),
                            "--spring.datasource.username=" + cfg("PG_USER", "zorrodev"),
                            "--spring.datasource.password=" + cfg("PG_PASSWORD", "zorrodev"),
                            "--spring.datasource.driver-class-name=org.postgresql.Driver",
                            "--spring.liquibase.enabled=true",
                            "--spring.profiles.active=test",
                            "--spring.main.web-application-type=none",
                            "--app.filesDir=target/files",
                            "--zorrobpm.engine.timer-poll-interval-ms=3600000",
                            "--spring.rabbitmq.listener.simple.auto-startup=false");
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

    /** Тот же порядок разрешения, что в {@link PostgresIT}: ENV → system property → дефолт. */
    private static String cfg(String key, String dflt) {
        String v = System.getenv(key);
        if (v == null) {
            v = System.getProperty(key);
        }
        return v != null ? v : dflt;
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + cfg("PG_HOST", "127.0.0.1") + ":" + cfg("PG_PORT", "5432")
                + "/" + cfg("PG_DB", "zorrobpm-db") + "?sslmode=disable";
    }

    private void writeObservation(String id, CrossCuttingScenarioSupport.Observation observation) {
        try {
            Files.createDirectories(ARTIFACT_DIR);
            ObjectMapper mapper = new ObjectMapper();
            List<Map<String, Object>> rows = new ArrayList<>();
            if (Files.exists(OURS_PG)) {
                Map<String, Object> existing = mapper.readValue(Files.readString(OURS_PG), Map.class);
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
            Files.writeString(OURS_PG,
                    mapper.writeValueAsString(Map.of("engine", "zorro", "db", "postgres", "outcomes", rows)));
        } catch (Exception e) {
            throw new IllegalStateException("cannot write the observation artifact", e);
        }
    }

    /** Сверка наблюдения с прибитой PG-частью факта. */
    @SuppressWarnings("unchecked")
    private void assertPinned(String id, CrossCuttingScenarioSupport.Observation observation) {
        Map<String, Map<String, Object>> pinned = loadPinnedFacts();
        Map<String, Object> fact = pinned.get(id);
        assertThat(fact).as("pinned fact for %s exists", id).isNotNull();
        Map<String, Object> pinnedPg = (Map<String, Object>) fact.get("pg");
        assertThat(pinnedPg).as("pinned fact for %s has the PostgreSQL part", id).isNotNull();

        List<String> problems = new ArrayList<>();
        if (!String.valueOf(pinnedPg.get("status")).equals(observation.status())) {
            problems.add(id + ": статус на PG изменился: было=" + pinnedPg.get("status")
                    + " стало=" + observation.status());
        }
        Map<String, Object> pinnedVars = (Map<String, Object>) pinnedPg.get("vars");
        if (pinnedVars != null && !normalizeVars(pinnedVars).equals(normalizeVars(observation.vars()))) {
            problems.add(id + ": переменные на PG изменились: было=" + pinnedVars + " стало=" + observation.vars());
        }
        List<String> pinnedLeaks = (List<String>) pinnedPg.get("leaks");
        if (pinnedLeaks != null && !normalizeLeaks(pinnedLeaks).equals(normalizeLeaks(observation.leaks()))) {
            problems.add(id + ": нарушения инварианта утечек на PG изменились: было=" + normalizeLeaks(pinnedLeaks)
                    + " стало=" + normalizeLeaks(observation.leaks()));
        }
        List<String> pinnedIncidents = (List<String>) pinnedPg.get("incidents");
        if (pinnedIncidents != null && !pinnedIncidents.equals(observation.incidents())) {
            problems.add(id + ": инциденты на PG изменились: было=" + pinnedIncidents + " стало=" + observation.incidents());
        }
        Map<String, Object> pinnedExtra = (Map<String, Object>) pinnedPg.get("extra");
        if (pinnedExtra != null && !normalizeVars(pinnedExtra).equals(normalizeVars(observation.extra()))) {
            problems.add(id + ": axis-факты на PG изменились: было=" + pinnedExtra + " стало=" + observation.extra());
        }
        assertThat(problems).as("cross-cutting slice divergences from the pinned PG facts (%s)", id).isEmpty();
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

    private List<String> normalizeLeaks(List<String> leaks) {
        List<String> out = new ArrayList<>();
        for (String leak : leaks == null ? List.<String>of() : leaks) {
            out.add(leak.replaceAll("instance=[0-9a-f-]{36}\\s*", ""));
        }
        return out;
    }

    private Map<String, Object> normalizeVars(Map<String, ?> vars) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, ?> e : vars.entrySet()) {
            out.put(e.getKey(), e.getValue() == null ? null : String.valueOf(e.getValue()));
        }
        return out;
    }
}
