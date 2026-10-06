package com.zorrodev.bpm.app;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-CFG-1, критерий 3 (сторожевой тест) — «при появлении в любом модуле
 * {@code application.properties} с {@code ${ENV}}-плейсхолдером, не импортированного
 * приложением, сборка краснеет».
 *
 * <p>Дефект, который закрывается, стоил дороже всех прочих находок этого WO: он был
 * НЕВИДИМ. Одноимённые {@code application.properties} в трёх модулях компилировались,
 * проходили все тесты модулей, и 22 ручки движка при этом просто не существовали в
 * задеплоенном приложении — оператор задавал переменную, а приложение работало на
 * умолчании, не сказав ни слова. Ни один тест этого не ловил, потому что тесты движка
 * запускаются в его собственном classpath, где {@code src/test/resources/application.properties}
 * закрывает и так.
 *
 * <p>Четыре стража, каждый на свой класс регрессии:
 *
 * <ol>
 *   <li><b>A (структурный).</b> Ни один модуль, кроме {@code zorrobpm-app}, не имеет права
 *       держать {@code src/main/resources/application.properties}: имя файла — и есть причина
 *       перекрытия, а не деталь оформления. Возврат старого имени краснеет здесь же.</li>
 *   <li><b>B (достижимость).</b> Каждый {@code ${ENV}} плейсхолдер любого properties-файла
 *       модуля обязан быть объявлен в app-файле или подключён аннотацией {@code @PropertySource}
 *       где-либо в сборке. Новый файл в новом модуле без подключения — тоже красный сценарий.</li>
 *   <li><b>C (профильные документы).</b> {@code application-&lt;profile&gt;.} документы
 *       разрешаются тем же «первым на classpath» правилом, что и основной файл: если два
 *       модуля заведут один и тот же профиль, один из них снова исчезнет молча. Сейчас в
 *       дереве ровно один такой файл ({@code application-prod.yml} в zorrobpm-rest), и
 *       зондируется именно он — не «забыть», а «считать, что он работает».</li>
 *   <li><b>D (defaults совпадают с compose).</b> Для каждого {@code ${ENV}} плейсхолдера
 *       импортируемых файлов переменная обязана быть объявлена в {@code docker-compose.yml}, и
 *       ЕЁ умолчание обязано совпадать с умолчанием properties-файла. Раньше это требование
 *       механизировал гейт G15 — но по glob'у {@code application*.properties}, а после
 *       переименования файла движка этот glob его больше не видит. Стража возвращает эту
 *       проверку внутрь сборки и заодно закрывает найденную при этом ловушку: пустой
 *       {@code ${VAR:-}} в compose даёт в контейнере ПУСТУЮ строку, а Spring применяет
 *       умолчание {@code ${VAR:default}} только когда переменной НЕТ (проверено живьём,
 *       см. {@link EmptyEnvVarBreaksContextTest}) — то есть умолчание обязано быть явным.</li>
 * </ol>
 *
 * <p>Тест НЕ поднимает Spring-контекст: он проверяет форму конфигурации на диске, то есть
 * ровно то, что компилятор и модульные тесты пропускают по определению. Живая проверка
 * доездания значений — в {@link Cfg1EnginePropertiesEnvBindingTest}.
 */
class ModuleConfigImportGuardTest {

    private static final String APP_PROPERTIES = "zorrobpm-app/src/main/resources/application.properties";

    /** Классы, подключающие конфигурацию модуля через аннотацию PropertySource. */
    private static final Pattern PROPERTY_SOURCE =
            Pattern.compile("@PropertySource\\(\\s*(?:value\\s*=\\s*)?\"classpath:([^\"]+)\"");

    /** Метка объявления «пусто, когда переменная не задана» (${VAR:-}). */
    private static final String EMPTY_DEFAULT = "<<empty>>";

    private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)(:[^}]*)?}");

    /**
     * Модуль вне сборки приложения: zorrobpm-client — отдельный SDK для чужих приложений, он
     * намеренно НЕ входит в classpath zorrobpm-app и грузит свой файл через
     * {@code @PropertySource("classpath:zorrobpm-client.properties")} в ClientConfiguration:21.
     * Проверять его на достижимость через импорт app-файла бессмысленно — такого импорта и не
     * должно быть. Перекрыть конфигурацию приложения он не может по построению: своего
     * {@code application.properties} у него нет, а это проверяет Guard A по всему дереву.
     */
    private static final Set<String> MODULES_OUTSIDE_APP_ASSEMBLY = Set.of("zorrobpm-client");

    /**
     * Переменные, для которых умолчание compose НАМЕРЕННО расходится с умолчанием
     * properties-файла. Без этого списка Guard D проверял бы равенство умолчаний повсюду и
     * требовал бы их совпадения — а расхождение здесь осознанное.
     *
     * <p>Почему список вообще нужен, а не «проверять всё подряд»: механизированная проверка
     * равенства умолчаний раньше принадлежала гейту G15, но его glob — {@code application*.properties},
     * и после переименования файла движка в {@code zorrobpm-engine.properties} он туда больше не
     * попадает. Без списка и этой проверки равенство держалось бы только на дисциплине, то есть
     * ровно на том, что этот WO и чинит в других местах.
     *
     * <p>Единственная запись: флаг CR-01. Свойство по умолчанию {@code false} — порядок выката
     * «сначала воркеры, потом флаг»; наш compose (движок и http-connector одного релиза) включает
     * его в {@code true}. Обоснование — в самом {@code docker-compose.yml} рядом с объявлением.
     */
    private static final Map<String, String> ALLOWED_DEFAULT_DIVERGENCE = Map.of(
        "ZORROBPM_ENGINE_DISPATCH_PHASE_STAMPING",
        "свойство по умолчанию false (порядок выката «сначала воркеры, потом флаг»), наш compose "
            + "включает CR-01 в true — engine и http-connector одного релиза",
        "RABBITMQ_MGMT_BASE_URL",
        "внутри docker-сети Management API доступен по имени сервиса (http://rabbitmq:15672/rabbitmq — "
            + "путь обязателен при management.path_prefix, см. RabbitMqMgmtPathPrefixWiringGuardTest), "
            + "а умолчание файла — localhost для локального запуска вне compose");

    /** Модули репозитория, у которых есть {@code src/main/resources}. */
    private static List<Path> modulesWithMainResources() throws IOException {
        Path root = repoRoot();
        try (Stream<Path> children = Files.list(root)) {
            List<Path> modules = new ArrayList<>();
            children.filter(Files::isDirectory)
                .filter(p -> Files.isDirectory(p.resolve("src/main/resources")))
                .sorted()
                .forEach(modules::add);
            return modules;
        }
    }

    // ------------------------------------------------------------------ стражи A и B

    @Test
    void onlyTheAppModuleMayShipAnApplicationProperties() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path module : modulesWithMainResources()) {
            Path shadowing = module.resolve("src/main/resources/application.properties");
            if (Files.exists(shadowing) && !"zorrobpm-app".equals(module.getFileName().toString())) {
                offenders.add(module.getFileName() + " -> " + shadowing);
            }
        }
        assertThat(offenders)
            .as("Spring Boot грузит classpath:/application.properties как ОДИН ресурс — первый "
                + "на classpath, а в fat-jar это BOOT-INF/classes (zorrobpm-app). Одноимённый "
                + "файл в любом другом модуле НЕ читается и не пишет об этом ни слова "
                + "(WO-CFG-1: 24 ручки движка были мертвы именно так). Переименуй файл и "
                + "подключи его через @PropertySource из своего модуля (например "
                + "com.zorrodev.bpm.engine.configuration.EnginePropertiesSource). "
                + "spring.config.import ТУТ НЕЛЬЗЯ: измерено, что в Spring Boot 4 импортированный "
                + "документ получает приоритет выше импортирующего и перебивает app-файл")
            .isEmpty();
    }

    @Test
    void everyEnvPlaceholderOfModulePropertiesIsReachableThroughTheAppFile() throws IOException {
        Set<String> appKeys = propertyKeysOf(repoRoot().resolve(APP_PROPERTIES));
        Set<String> loadedFiles = propertySourceResources();

        List<String> unreachable = new ArrayList<>();
        for (Path module : modulesWithMainResources()) {
            if (MODULES_OUTSIDE_APP_ASSEMBLY.contains(module.getFileName().toString())) {
                continue;
            }
            Path resources = module.resolve("src/main/resources");
            try (Stream<Path> files = Files.list(resources)) {
                for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".properties"))
                        .toList()) {
                    String name = file.getFileName().toString();
                    for (Map.Entry<String, String> entry : envPlaceholderKeys(read(file)).entrySet()) {
                        if (appKeys.contains(entry.getKey())) {
                            continue;
                        }
                        if (!loadedFiles.contains(name)) {
                            unreachable.add(module.getFileName() + "/" + name + ": ключ "
                                + entry.getKey() + " (" + entry.getValue() + ") не объявлен ни в "
                                + APP_PROPERTIES + ", ни не подключён через @PropertySource — "
                                + "переменная оператора разрешится в пустоту, и ручка молча "
                                + "останется на значении по умолчанию (WO-CFG-1)");
                        }
                    }
                }
            }
        }
        assertThat(unreachable)
            .as("ключ с ${ENV} из файла модуля недостижим, если этот файл не импортирован "
                + "app-файлом: переменная оператора разрешится в пустоту, и ручка молча "
                + "останется на значении по умолчанию (WO-CFG-1)")
            .isEmpty();
    }

    /**
     * Guard B, продолжение: не только «файл назван в {@code @PropertySource}», но и «его можно
     * открыть» — опечатка в пути переживается молча (ресурс просто не находится), и конфиг
     * модуля исчезает, не оставив следу.
     */
    @Test
    void everyPropertySourceResourceExistsOnTheClasspath() throws IOException {
        List<String> missing = new ArrayList<>();
        for (String resource : propertySourceResources()) {
            if (getClass().getClassLoader().getResource(resource) == null) {
                missing.add(resource);
            }
        }
        assertThat(missing)
            .as("файл назван в @PropertySource, но его нет в classpath. @PropertySource на "
                + "отсутствующий ресурс НЕ даёт ошибки — конфиг просто не применится, а "
                + "ModuleConfigImportGuardTest это и поймать не сможет, потому что проверяет "
                + "имя, а не файл")
            .isEmpty();
    }

    // ------------------------------------------------------------------ страж C

    @Test
    void noTwoModulesShipTheSameProfileDocument() throws IOException {
        Map<String, List<String>> byProfile = new LinkedHashMap<>();
        for (Path module : modulesWithMainResources()) {
            Path resources = module.resolve("src/main/resources");
            try (Stream<Path> files = Files.list(resources)) {
                files.filter(p -> p.getFileName().toString().matches("application-.+\\.ya?ml"))
                    .forEach(p -> byProfile
                        .computeIfAbsent(p.getFileName().toString(), k -> new ArrayList<>())
                        .add(module.getFileName().toString()));
            }
        }
        List<String> clashes = byProfile.entrySet().stream()
            .filter(e -> e.getValue().size() > 1)
            .map(e -> e.getKey() + " -> " + e.getValue())
            .toList();
        assertThat(clashes)
            .as("профильный документ резолвится тем же правилом «первый на classpath», что и "
                + "application.properties: два модуля с одним именем профиля означают, что один "
                + "из них снова исчезнет молча (сейчас в дереве ровно один — application-prod.yml "
                + "в zorrobpm-rest, он и загружается)")
            .isEmpty();
    }

    /** Сам профильный документ остаётся в своём имени намеренно — проверяем, что он на месте. */
    @Test
    void restProfileDocumentIsStillWhereSpringLooksForIt() {
        assertThat(getClass().getClassLoader().getResource("application-prod.yml"))
            .as("application-prod.yml из zorrobpm-rest перекрывать нечем (у zorrobpm-app такого "
                + "файла нет), поэтому он остаётся под своим именем и продолжает загружаться — "
                + "переименовывать его в этом WO нельзя")
            .isNotNull();
    }

    // ------------------------------------------------------------------ страж D

    /**
     * Каждая {@code ${ENV}} переменная из импортируемых файлов объявлена в сервисе приложения
     * обоих compose, и ни одна из них не объявлена ПУСТОЙ строкой.
     *
     * <p>Что и зачем проверяется — два разных требования, и их важно не смешивать:
     *
     * <ul>
     *   <li><b>D1 — объявлена.</b> В {@code docker-compose.yml} у сервиса {@code app} НЕТ
     *       {@code env_file}: в контейнер попадает только то, что перечислено в
     *       {@code environment:}. Переменная, которой там нет, недостижима для оператора —
     *       сколько бы её ни стояло в {@code .env}. В {@code docker-compose.multi.yml}
     *       {@code env_file: .env} есть, поэтому там отсутствие в {@code environment:}
     *       законно.</li>
     *   <li><b>D2 — не пустая.</b> Объявление {@code ${VAR:-}} кладёт в контейнер ПУСТУЮ
     *       строку, а Spring применяет умолчание {@code ${VAR:default}} только когда переменной
     *       нет, — то есть такая запись затирает умолчание properties-файла (см.
     *       {@link EmptyEnvVarBreaksContextTest}). Раньше это касалось девяти ручек.</li>
     * </ul>
     *
     *   <li><b>D3 — умолчание совпадает.</b> Если compose объявляет переменную как
     *       {@code ${VAR:-литерал}} (без вложенных подстановок), то этот литерал обязан совпадать с
     *       умолчанием properties-файла — иначе «правильным» станет неверное значение, потому что
     *       compose побеждает файл. Литералы-исключения перечислены в
     *       {@link #ALLOWED_DEFAULT_DIVERGENCE} вместе с причиной; там же — почему проверка нужна
     *       именно вручную после переименования файла движка (glob гейта G15 его больше не видит).
     *       Вложенные подстановки (например {@code ${DB_URL:-jdbc:…/${DB_NAME:-…}}}) и голые
     *       литералы (например {@code RABBITMQ_HOST: rabbitmq} в soak-риге) D3 не проверяет: это
     *       осознанный deployment override, а не «умолчание properties-файла».</p>
     *
     * <p>Раньше D1+D2 механизировал гейт G15 по glob'у {@code application*.properties}; после
     * переименования файла движка этот glob его больше не видит, поэтому проверка возвращена
     * внутрь сборки.
     */
    @Test
    void everyImportedEnvVarIsDeclaredInTheAppServiceAndNeverEmpty() throws IOException {
        Path root = repoRoot();
        List<String> problems = new ArrayList<>();

        for (String compose : List.of("docker-compose.yml", "docker-compose.multi.yml")) {
            String yaml = read(root.resolve(compose));
            String appService = appServiceBlock(yaml);
            Map<String, Declared> declared = composeDeclarations(appService);
            boolean envFilePassesEverything = yaml.contains("env_file");

            for (String moduleFile : List.of(
                    "zorrobpm-engine/src/main/resources/zorrobpm-engine.properties",
                    "zorrobpm-rest/src/main/resources/zorrobpm-rest.properties")) {
                for (Map.Entry<String, String> entry
                        : envPlaceholderKeys(read(root.resolve(moduleFile))).entrySet()) {
                    // matches(), а не find(): значение здесь И ЕСТЬ сам плейсхолдер целиком.
                    Matcher placeholder = ENV_PLACEHOLDER.matcher(entry.getValue());
                    if (!placeholder.matches()) {
                        continue;
                    }
                    String envName = placeholder.group(1);

                    if (!declared.containsKey(envName)) {
                        if (!envFilePassesEverything) {
                            problems.add(compose + ": " + envName + " (для " + entry.getKey()
                                + ") не объявлен в environment: сервиса приложения, а env_file в "
                                + "этом compose нет — оператор не достанет до этой ручки ни через "
                                + "что");
                        }
                        continue;
                    }
                    Declared declaration = declared.get(envName);
                    String composeValue = declaration.value();
                    if (composeValue.equals(EMPTY_DEFAULT)) {
                        problems.add(compose + ": " + envName + " объявлен как ${" + envName
                            + ":-} и в контейнер уходит ПУСТОЙ СТРОКОЙ. Spring применяет "
                            + "умолчание ${VAR:default} только когда переменной нет, а для "
                            + "пустой отдаёт пустую строку — умолчание " + entry.getKey()
                            + " будет затёрто (см. EmptyEnvVarBreaksContextTest)");
                    } else {
                        // D3: равенство умолчаний там, где compose объявляет «${VAR:-литерал}».
                        // Голые литералы — явные значения развёртывания, а не умолчания, и
                        // вложенные подстановки — вычисляемое выражение; и то и то выходит за
                        // предмет проверки.
                        String fileDefault = defaultOf(entry.getValue());
                        if (declaration.defaulted()
                                && fileDefault != null && !fileDefault.isEmpty()
                                && composeValue.indexOf("${") < 0
                                && !composeValue.equals(fileDefault)
                                && !ALLOWED_DEFAULT_DIVERGENCE.containsKey(envName)) {
                            problems.add(compose + ": " + envName + " умолчание '" + composeValue
                                + "' разошлось с умолчанием properties-файла '" + fileDefault
                                + "' для ключа " + entry.getKey() + " — compose побеждает файл, "
                                + "поэтому действующим окажется неверное значение. Либо выравнивай, "
                                + "либо (если расхождение осознанное) внеси его в "
                                + "ALLOWED_DEFAULT_DIVERGENCE этого стража с причиной");
                        }
                    }
                }
            }
        }
        assertThat(problems)
            .as("состав ${ENV} ручек и способ их доставки в контейнер обязаны быть осмысленными "
                + "после того, как файл движка начал читаться по-настоящему")
            .isEmpty();
    }

    // ------------------------------------------------------------------ инфраструктура

    /** Блок одного сервиса compose: от его заголовка до следующего заголовка сервиса. */
    private static String appServiceBlock(String yaml) {
        List<String> lines = List.of(yaml.split("\n", -1));
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).matches("^ {2}[a-z0-9-]+:.*")) {
                if (start >= 0) {
                    return String.join("\n", lines.subList(start, i));
                }
                if (lines.get(i).matches("^ {2}(app|app1):.*")) {
                    start = i + 1;
                }
            }
        }
        return start >= 0 ? String.join("\n", lines.subList(start, lines.size())) : "";
    }

    /**
     * Имя переменной → её объявление в блоке сервиса: значение целиком (может быть литералом
     * {@code rabbitmq} или вложенным {@code ${DB_URL:-jdbc:...}}), либо {@link #EMPTY_DEFAULT},
     * если оно записано как «пусто, когда не задано».
     */
    private static Map<String, Declared> composeDeclarations(String appService) {
        Map<String, Declared> result = new LinkedHashMap<>();
        Matcher m = Pattern.compile("(?m)^\\s+([A-Z][A-Z0-9_]+):\\s*(.+?)\\s*$").matcher(appService);
        while (m.find()) {
            String raw = m.group(2);
            if (raw.startsWith("${") && raw.endsWith("}")) {
                String inner = raw.substring(2, raw.length() - 1);
                int colon = inner.indexOf(":-");
                String defaults = colon >= 0 ? inner.substring(colon + 2) : "";
                result.put(m.group(1),
                    new Declared(defaults.isEmpty() ? EMPTY_DEFAULT : defaults, true));
            } else {
                // Голый литерал (RABBITMQ_HOST: rabbitmq, APP_FILES_DIR: /app/files) — это НЕ
                // умолчание, а явное значение развёртывания. Сравнивать его с умолчанием
                // properties-файла бессмысленно, и D3 такое объявление не проверяет.
                result.put(m.group(1), new Declared(raw, false));
            }
        }
        return result;
    }

    /** Что именно compose объявил: значение и было ли оно подставкой «иначе умолчание». */
    private record Declared(String value, boolean defaulted) {
    }

    /**
     * Ресурсы, подключённые через {@code @PropertySource} где-либо в дереве — то, что
     * приложение обязано подключить. Ищутся по исходникам, а не через Spring: стража должна
     * работать и тогда, когда подключение сломано (иначе она не стража).
     */
    private static Set<String> propertySourceResources() throws IOException {
        Set<String> names = new LinkedHashSet<>();
        Path root = repoRoot();
        for (Path module : modulesWithMainResources()) {
            if (MODULES_OUTSIDE_APP_ASSEMBLY.contains(module.getFileName().toString())) {
                continue;
            }
            // Только src/main/java: подключение в тестовом коде на конфигурацию приложения
            // не влияет. Побочный эффект этого ограничения — стража не находит саму себя
            // (этот файл лежит в src/test, и упоминание аннотации в его javadoc иначе
            // превратилось бы в «подключён zorrobpm-client.properties»).
            Path mainJava = module.resolve("src/main/java");
            if (!Files.isDirectory(mainJava)) {
                continue;
            }
            try (Stream<Path> javaFiles = Files.walk(mainJava)) {
                for (Path java : javaFiles.filter(p -> p.toString().endsWith(".java")).toList()) {
                    Matcher m = PROPERTY_SOURCE.matcher(stripJavaComments(read(java)));
                    while (m.find()) {
                        names.add(m.group(1));
                    }
                }
            }
        }
        return names;
    }

    /**
     * Java без комментариев и строковых литералов.
     *
     * <p><b>Зачем это здесь, а не «для красоты».</b> Поиск идёт по исходнику как по тексту, и
     * без вырезания комментариев ЗАКОММЕНТИРОВАННАЯ аннотация считалась бы действующей: автор
     * отключил бы подключение, оставив строку с аннотацией в комментарии, и стража сказала бы
     * «файл подключён». Это не гипотетический сценарий — так сделал независимый проверяющий
     * (verifier, находка 6 вердикта на `fb6e24c2`), и Guard B остался зелёным при отключённом
     * подключении. Комментарии вырезаются целиком, строковые литералы заменяются на
     * заглушку (иначе `//` внутри строки съел бы остаток файла).
     */
    private static String stripJavaComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean inBlockComment = false;
        boolean inLineComment = false;
        boolean inString = false;
        boolean inChar = false;
        boolean escaped = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                    out.append(c);
                }
                continue;
            }
            // Строковый литерал сохраняется ДОСЛОВНО (аргумент аннотации PropertySource —
            // это строка, и вычистив её содержимое, мы поймали бы «аннотация есть» только
            // потому, что есть комментарий). Задача этого метода — убрать комментарии, а не
            // содержимое строк; опасность «//» внутри строки снята тем, что мы внутри строки
            // и не смотрим на следующий символ как на начало комментария.
            if (inString) {
                out.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (inChar) {
                out.append(c);
                if (c == '\\') {
                    if (i + 1 < source.length()) {
                        out.append(source.charAt(i + 1));
                        i++;
                    }
                } else if (c == '\'') {
                    inChar = false;
                }
                continue;
            }
            if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '/') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
                continue;
            }
            if (c == '\'') {
                inChar = true;
                out.append(c);
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static Set<String> propertyKeysOf(Path path) {
        Set<String> keys = new LinkedHashSet<>();
        for (String line : read(path).split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                keys.add(trimmed.substring(0, eq).trim());
            }
        }
        return keys;
    }

    /** Умолчание внутри плейсхолдера {@code ${VAR:…}}, либо {@code null}, если умолчания нет. */
    private static String defaultOf(String placeholder) {
        Matcher m = ENV_PLACEHOLDER.matcher(placeholder);
        if (!m.matches() || m.group(2) == null || m.group(2).length() <= 1) {
            return null;
        }
        return m.group(2).substring(1);
    }

    static Map<String, String> envPlaceholderKeys(String properties) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : properties.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            Matcher m = ENV_PLACEHOLDER.matcher(trimmed.substring(eq + 1));
            if (m.find()) {
                result.put(trimmed.substring(0, eq).trim(), m.group());
            }
        }
        return result;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("не прочитан " + path, e);
        }
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("pom.xml"))
                && Files.exists(dir.resolve("docker-compose.yml"))
                && Files.isDirectory(dir.resolve("zorrobpm-engine"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("корень репозитория не найден");
    }
}