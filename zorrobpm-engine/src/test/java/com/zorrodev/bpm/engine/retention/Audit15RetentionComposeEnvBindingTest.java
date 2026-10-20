package com.zorrodev.bpm.engine.retention;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-AUDIT-15 (F-11): retention events/outbox (WO-AUDIT-7) — ручки написаны, а
 * ДО КОНТЕЙНЕРА НЕ ДОЕЗЖАЮТ.
 *
 * <p><b>Дефект, который закрывается.</b> Чистка таблиц {@code events} и
 * {@code outbox} реализована (WO-AUDIT-7: {@code RetentionConfig.eventsTtlDays} /
 * {@code outboxTtlDays}, проходы в {@link RetentionJob}, метрики). Ручки объявлены
 * в {@code zorrobpm-engine.properties} как
 * {@code ${ZORROBPM_ENGINE_RETENTION_EVENTS_TTL_DAYS:0}} и т.д. Но в
 * {@code docker-compose.yml} у сервиса {@code app} НЕТ {@code env_file} — в
 * контейнер попадает ТОЛЬКО то, что перечислено в {@code environment:}. А там
 * этих четырёх имён не было. Итог: оператор ставил значение в {@code .env} на
 * хосте, контейнер его не видел, дефолт «0 = выключено» оставался в силе, и
 * таблицы {@code events}/{@code outbox} росли бесконечно — при полностью
 * рабочей реализации.
 *
 * <p>Уже существующие тесты этого не видели по двум разным причинам, и обе
 * важны: {@code Audit7RetentionEnvBindingTest} кладёт переменные в окружение
 * САМ (минуя compose) — он доказывает «env → бин», но не «compose → env»;
 * {@code Cfg1EnginePropertiesEnvBindingTest} поднимает боевой контекст с
 * подобранным словарём окружения — опять без compose. Связка «что оператор
 * пишет в {@code .env} → что compose пробросит в контейнер» не была проверена
 * ни одним тестом.
 *
 * <p><b>Почему имя переменной берётся ИЗ САМОГО compose, а не из константы
 * теста.</b> Проверяемое утверждение — «значение, объявленное в compose, доезжает
 * до бина». Если подставить имя руками, тест останется зелёным и при сломанном
 * compose (relaxed binding работает для любого корректного имени) — то есть он
 * не поймал бы ровно тот дефект, который здесь закрывается. Источник имени —
 * тот же, откуда его берёт оператор.
 *
 * <p><b>Почему окружение эмулируется через {@link SystemEnvironmentPropertySource}.</b>
 * V11: имя переменной в контейнере разрешается в ключ свойства тем же кодом
 * Spring, что и в проде. Подмена делается на уровне «словаря окружения»
 * целиком.
 *
 * <p>Мутации, которые обязаны ронять этот тест:
 * <ul>
 *   <li>убрать строку {@code ZORROBPM_ENGINE_RETENTION_EVENTS_TTL_DAYS} из
 *       {@code environment:} сервиса {@code app} в {@code docker-compose.yml} —</li>
 *   <li>переименовать переменную в compose в укороченную форму (без
 *       {@code ENGINE_}/{@code RETENTION_}) —</li>
 *   <li>поставить в compose небезопасный дефолт (например 30) — дефолт обязан
 *       быть 0: включение чистки на проде — решение владельца, а не дефолт в
 *       compose;</li>
 *   <li>переименовать поле в {@link RetentionConfig} так, что бин перестаёт
 *       получать значение.</li>
 * </ul>
 */
class Audit15RetentionComposeEnvBindingTest {

    /** Ключи свойств движка → точная relaxed-binding форма имени переменной. */
    private static final Map<String, String> KEY_TO_ENV = Map.of(
        "zorrobpm.engine.retention.events-ttl-days", "ZORROBPM_ENGINE_RETENTION_EVENTS_TTL_DAYS",
        "zorrobpm.engine.retention.outbox-ttl-days", "ZORROBPM_ENGINE_RETENTION_OUTBOX_TTL_DAYS",
        "zorrobpm.engine.retention.dry-run", "ZORROBPM_ENGINE_RETENTION_DRY_RUN",
        "zorrobpm.engine.retention.batch-pause-ms", "ZORROBPM_ENGINE_RETENTION_BATCH_PAUSE_MS");

    /** Безопасный дефолт: чистка выключена (0), dry-run выключен, пауза 0. */
    private static final Map<String, String> SAFE_DEFAULTS = Map.of(
        "ZORROBPM_ENGINE_RETENTION_EVENTS_TTL_DAYS", "0",
        "ZORROBPM_ENGINE_RETENTION_OUTBOX_TTL_DAYS", "0",
        "ZORROBPM_ENGINE_RETENTION_DRY_RUN", "false",
        "ZORROBPM_ENGINE_RETENTION_BATCH_PAUSE_MS", "0");

    /** Compose-файлы, в которых ручка обязана быть объявлена. */
    private static final String[] COMPOSE_FILES = {"docker-compose.yml", "docker-compose.multi.yml"};

    private static final String ENGINE_PROPERTIES =
        "zorrobpm-engine/src/main/resources/zorrobpm-engine.properties";
    private static final String ENV_EXAMPLE = ".env.example";

    /** Объявление переменной в compose: {@code ИМЯ: ${ИМЯ:-дефолт}}. */
    private static final Pattern ENV_DECLARATION =
        Pattern.compile("(?m)^\\s+([A-Z][A-Z0-9_]+):\\s*\\$\\{\\1:-?([^}]*)}\\s*$");

    /** Плейсхолдер в properties-файле: {@code ${ИМЯ:дефолт}}. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)(:[^}]*)?}");

    // ------------------------------------------------------------------ 1: объявлено

    /**
     * Критерий F-11, часть 1: каждая из четырёх ручек объявлена в
     * {@code environment:} сервиса {@code app} — иначе оператор не достанется
     * до неё ни через что (в {@code docker-compose.yml} нет {@code env_file}).
     */
    @Test
    void retentionEnvVarsAreDeclaredInTheAppServiceOfEveryCompose() {
        for (String composeFile : COMPOSE_FILES) {
            Map<String, String> declared = envVarsDeclaredIn(composeFile);
            for (String envName : KEY_TO_ENV.values()) {
                assertThat(declared)
                    .as("%s обязан объявлять %s в environment: сервиса приложения — иначе "
                        + "значение из .env не попадёт в контейнер, и дефолт 0 (= выключено) "
                        + "останется в силе (WO-AUDIT-15, F-11)", composeFile, envName)
                    .containsKey(envName);
            }
        }
    }

    // ------------------------------------------------------- 2: дефолты безопасны

    /**
     * Критерий F-11, часть 2: дефолт в compose — безопасный (0/false) и
     * совпадает с дефолтом properties-файла. Совпадение обязано: compose
     * побеждает файл, и разъехавшийся дефолт означал бы, что «правильным»
     * окажется неверное значение.
     */
    @Test
    void composeDefaultsAreSafeAndMatchThePropertiesFile() {
        Map<String, String> fileDefaults = placeholderDefaults(ENGINE_PROPERTIES);

        for (String composeFile : COMPOSE_FILES) {
            Map<String, String> declared = envVarsDeclaredIn(composeFile);
            for (Map.Entry<String, String> entry : SAFE_DEFAULTS.entrySet()) {
                String envName = entry.getKey();
                String safe = entry.getValue();
                assertThat(declared)
                    .as("%s обязан объявлять %s", composeFile, envName)
                    .containsKey(envName);
                assertThat(declared.get(envName))
                    .as("дефолт %s в %s обязан быть безопасным (%s): включение чистки "
                        + "events/outbox на проде — решение владельца, а не дефолт в compose",
                        envName, composeFile, safe)
                    .isEqualTo(safe);
                assertThat(fileDefaults)
                    .as("ключ %s обязан быть объявлен в %s", envName, ENGINE_PROPERTIES)
                    .containsKey(envName);
                assertThat(fileDefaults.get(envName))
                    .as("дефолт %s в %s и в compose обязан совпадать", envName, ENGINE_PROPERTIES)
                    .isEqualTo(safe);
            }
        }
    }

    // ------------------------------------------------- 3: compose → env → настоящий бин

    /**
     * Критерий F-11, часть 3: значение, объявленное в compose, доезжает до
     * НАСТОЯЩЕГО бина {@link RetentionConfig} — тем же путём, что и в проде:
     * словарь окружения контейнера → {@code zorrobpm-engine.properties}
     * (тот файл, который читает прод) → бин.
     *
     * <p>Значение-сентинель отличается от любого дефолта, иначе ассерт прошёл
     * бы и при неработающей связке.
     */
    @Test
    void envValueDeclaredInCompose_reachesTheRealRetentionConfigBean() {
        Map<String, String> containerEnv = new LinkedHashMap<>();
        containerEnv.put("ZORROBPM_ENGINE_RETENTION_EVENTS_TTL_DAYS", "45");
        containerEnv.put("ZORROBPM_ENGINE_RETENTION_OUTBOX_TTL_DAYS", "60");
        containerEnv.put("ZORROBPM_ENGINE_RETENTION_DRY_RUN", "true");
        containerEnv.put("ZORROBPM_ENGINE_RETENTION_BATCH_PAUSE_MS", "50");

        // Имена берутся из compose — если compose их не объявляет, тест падает
        // ЗДЕСЬ (а не «проходит на подставленном имени»).
        for (String composeFile : COMPOSE_FILES) {
            Map<String, String> declared = envVarsDeclaredIn(composeFile);
            for (String envName : containerEnv.keySet()) {
                assertThat(declared)
                    .as("%s обязан объявлять %s", composeFile, envName)
                    .containsKey(envName);
            }
        }

        runnerWithContainerEnv(containerEnv).run(ctx -> {
            assertThat(ctx).as("контекст с ручками retention обязан подниматься").hasNotFailed();
            RetentionConfig config = ctx.getBean(RetentionConfig.class);
            assertThat(config.getEventsTtlDays())
                .as("events-TTL обязан доехать до бина (связка compose → env → %s)",
                    RetentionConfig.class.getSimpleName())
                .isEqualTo(45);
            assertThat(config.getOutboxTtlDays()).isEqualTo(60);
            assertThat(config.isDryRun()).isTrue();
            assertThat(config.getBatchPauseMs()).isEqualTo(50L);
        });
    }

    /**
     * Контроль-негатив: укороченное имя (без {@code ENGINE_}/{@code RETENTION_})
     * ключ не питает — иначе «работающая» ручка была бы случайной.
     */
    @Test
    void shortenedEnvNameDoesNotResolve() {
        Map<String, String> containerEnv = new LinkedHashMap<>();
        containerEnv.put("ZORROBPM_RETENTION_EVENTS_TTL_DAYS", "45");

        runnerWithContainerEnv(containerEnv).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(RetentionConfig.class).getEventsTtlDays())
                .as("укороченное имя обязано НЕ доезжать (остаётся дефолт 0)")
                .isEqualTo(0);
        });
    }

    /**
     * Критерий F-11, часть 4: оператор узнаёт о ручках из {@code .env.example}.
     * Файл — шаблон хостового {@code .env}, а значения в {@code .env} живут
     * на хосте (деплой их не копирует — {@code .env} исключён из wipe), то
     * есть без документации переменной на хосте просто неоткуда взять.
     *
     * <p>Значения в шаблоне — тоже безопасные (0/false): оператор копирует
     * файл на прод как есть, и ненулевой TTL там означал бы, что чистка
     * включается вместе с копированием шаблона, а не решением владельца.
     */
    @Test
    void envExampleDocumentsTheRetentionKnobs() {
        String example = read(repoRoot().resolve(ENV_EXAMPLE));
        for (Map.Entry<String, String> entry : SAFE_DEFAULTS.entrySet()) {
            String envName = entry.getKey();
            assertThat(example)
                .as("%s обязан упоминать %s — иначе оператор на хосте не знает, что есть такая ручка",
                    ENV_EXAMPLE, envName)
                .contains(envName);
            assertThat(valueInEnvExample(example, envName))
                .as("значение %s в %s обязано быть безопасным (%s): файл копируется на прод "
                    + "как есть, и ненулевое значение включало бы чистку без решения владельца",
                    envName, ENV_EXAMPLE, entry.getValue())
                .isEqualTo(entry.getValue());
        }
    }

    /** Значение переменной в {@code .env.example} (пустая строка — не задано). */
    private static String valueInEnvExample(String example, String envName) {
        for (String line : example.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(envName + "=")) {
                return trimmed.substring(envName.length() + 1).trim();
            }
        }
        return "";
    }

    // ------------------------------------------------------------------ инфраструктура

    /** Все {@code ИМЯ: ${ИМЯ:-дефолт}} из compose-файла: имя → дефолт. */
    private static Map<String, String> envVarsDeclaredIn(String composeFile) {
        String yaml = read(repoRoot().resolve(composeFile));
        Map<String, String> result = new LinkedHashMap<>();
        Matcher m = ENV_DECLARATION.matcher(yaml);
        while (m.find()) {
            result.put(m.group(1), m.group(2));
        }
        return result;
    }

    /** Все {@code ${ИМЯ:дефолт}} из properties-файла: имя переменной → дефолт. */
    private static Map<String, String> placeholderDefaults(String relativePath) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : read(repoRoot().resolve(relativePath)).split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            Matcher m = PLACEHOLDER.matcher(trimmed.substring(eq + 1));
            if (m.find()) {
                String name = m.group(1);
                String def = m.group(2);
                result.put(name, def == null || def.length() <= 1 ? "" : def.substring(1));
            }
        }
        return result;
    }

    /**
     * Тестовая замена «системного» окружения — того самого класса, которым
     * контейнер доезжает до ключа свойства.
     */
    private static ApplicationContextRunner runnerWithContainerEnv(Map<String, String> containerEnv) {
        Map<String, Object> asObjectMap = new LinkedHashMap<>(containerEnv);
        return new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("containerEnv", asObjectMap)))
            .withUserConfiguration(RetentionBeans.class);
    }

    /**
     * Боевой контекст: настоящий {@code zorrobpm-engine.properties} (тот файл,
     * который читает прод, и единственный источник этих ключей) и настоящий
     * бин {@link RetentionConfig} с {@code @ConfigurationProperties}-биндингом.
     *
     * <p><b>Здесь НЕТ {@code @Configuration}, и это не небрежность.</b> В модуле
     * лежит {@code TestMain} с {@code @SpringBootApplication}, а он сканирует
     * компоненты по {@code com.zorrodev.bpm.engine.**} — то есть ВКЛЮЧАЯ
     * test-classes. Аннотированный стереотипом класс из тестовых исходников
     * поэтому становится бином в каждом {@code @SpringBootTest(classes =
     * TestMain.class)} модуля: {@code @EnableConfigurationProperties} на таком
     * классе зарегистрировал бы ВТОРОГО {@code RetentionConfig} рядом с
     * component-scanned, и {@code NoUniqueBeanDefinitionException} ронял бы
     * контекст во всех чужих тестах модуля (наблюдалось: 331 ошибок в полном
     * прогоне). Без стереотипа класс остаётся «лайтовым» кандидатом
     * конфигурации: {@code withUserConfiguration} его регистрирует, а
     * component scan не видит. Прецедент — тот же приём в
     * {@code C836ComposeEnvBindingTest}.
     */
    @EnableConfigurationProperties(RetentionConfig.class)
    @PropertySource("classpath:zorrobpm-engine.properties")
    static class RetentionBeans {
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("docker-compose.yml"))
                && Files.isDirectory(dir.resolve("zorrobpm-engine"))
                && Files.exists(dir.resolve(ENV_EXAMPLE))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("корень репозитория не найден (cwd=" + Path.of("") + ")");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("не прочитан " + path, e);
        }
    }
}
