package com.zorrodev.bpm.app;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-OPS-25: сторож против «молчащих» тестов — классов, которых НЕТ ни в одном
 * include'е surefire/failsafe, а значит пайплайн их никогда не запускает.
 *
 * <p><b>Дефект, который закрывается.</b> Красная команда WO-C8-40 (находка Б-1) обнаружила
 * {@code MiSubProcessApprovalRouteIT}: суффикс {@code *IT.java} не входит ни в
 * surefire-дефолт ({@code **}{@code /}{@code *Test*.java}), ни в failsafe-includes engine-pom
 * ({@code **}{@code /}{@code *IntegrationTests.java}, {@code **}{@code /}{@code *PgIT.java},
 * {@code **}{@code /}{@code *ChaosIT.java}) —
 * тест был «зелёным» по отчёту и НИ РАЗУ не выполнялся в CI. Те же pre-existing сироты в
 * engine: {@code CallActivityRecursionIT}, {@code Eng29/30/33…IT},
 * {@code IncidentResolveAfterManualCompleteIT}, {@code OutboxTraceParentMigrationIT},
 * {@code DomainTransactionalBoundaryIT}, {@code RegistrationRateLimitWiringIT} — они
 * протухли (контракт менялся, тесты не переписывались) и были переименованы в рамках
 * WO-OPS-25, чтобы реально выполняться.
 *
 * <p><b>Как работает сторож.</b> Единственный источник правды — include/exclude паттерны в
 * {@code pom.xml} самих модулей: тест их ПАРСИТ с диска (никакого продублированного
 * списка в коде теста), считает для каждого класса {@code src/test/java} покрытым он
 * surefire'ом, failsafe'ом или НИ ОДНИМ. Класс-сирота = файл с реальными тест-методами
 * ({@code @Test} и семейство), который не покрыт ни одним include'ом. Такой класс JUnit
 * никогда не запустит — он «зелёный» только потому, что о нём никто не знает.
 *
 * <p>Что сторож НЕ считает сиротой (и почему):
 * <ul>
 *   <li>класс без тест-методов (хелперы, базовые классы, JUnit-расширения) — не тест;</li>
 *   <li>{@code abstract} класс — JUnit его и так не инстанцирует, а его наследники
 *       проверяются отдельными файлами;</li>
 *   <li>комментарии и строковые литералы вырезаются перед разбором, чтобы упоминание
 *       {@code @Test} в javadoc не давало ложного срабатывания.</li>
 * </ul>
 *
 * <p><b>Где живёт и почему.</b> Модуль {@code zorrobpm-app} — сборка всего приложения;
 * там же уже лежат кросс-модульные сторожи ({@link DeployCopyListGuardTest},
 * {@link ModuleConfigImportGuardTest}, {@link Cfg1EnginePropertiesEnvBindingTest}),
 * которые читают файлы других модулей и корневого pom. Сторож обходит ВСЕ модули
 * репозитория (в дереве есть и другие сироты в других модулях — их видно отчётом).
 *
 * <p>Правки {@code ci/} и {@code .gitlab-ci.yml} этот тест не требует: он сам по себе
 * исполняется в фазе {@code test} (job {@code test:backend}), поэтому «шаг в ci/» —
 * отдельное предложение (см. отчёт WO-OPS-25), а не условие этого сторожа.
 */
class OrphanTestClassGuardTest {

    /** Дефолтные include'ы surefire (Maven Surefire Plugin 3.x). */
    private static final List<String> SUREFIRE_DEFAULT_INCLUDES = List.of(
        "**/Test*.java",
        "**/*Test.java",
        "**/*Tests.java",
        "**/*TestCase.java");

    /** Дефолтные include'ы failsafe (Maven Failsafe Plugin 3.x). */
    private static final List<String> FAILSAFE_DEFAULT_INCLUDES = List.of(
        "**/IT*.java",
        "**/*IT.java",
        "**/*ITCase.java");

    /**
     * Классы, которые в рамках WO-OPS-25 переименованы из {@code *IT.java} в
     * {@code *IntegrationTests.java} — чтобы реально попасть в failsafe-includes
     * engine-pom. Список — регрессионная страховка: если кто-то вернёт имя {@code *IT},
     * тест {@link #formerOrphanRenames_stayCoveredByFailsafeIncludes()} краснеет.
     */
    private static final List<String> FORMER_ORPHANS_RENAMED = List.of(
        "com/zorrodev/bpm/engine/integration/CallActivityRecursionIntegrationTests.java",
        "com/zorrodev/bpm/engine/integration/Eng29FeelNullIoMappingIncidentIntegrationTests.java",
        "com/zorrodev/bpm/engine/integration/Eng30LenientModeNoIncidentIntegrationTests.java",
        "com/zorrodev/bpm/engine/integration/Eng33NowIoMappingIntegrationTests.java",
        "com/zorrodev/bpm/engine/integration/IncidentResolveAfterManualCompleteIntegrationTests.java",
        "com/zorrodev/bpm/engine/scheduler/OutboxTraceParentMigrationIntegrationTests.java",
        "com/zorrodev/bpm/engine/service/DomainTransactionalBoundaryIntegrationTests.java",
        "com/zorrodev/bpm/engine/service/RegistrationRateLimitWiringIntegrationTests.java");

    /** Аннотация тест-метода (JUnit 5 + Jupiter-параметризованные). */
    private static final Pattern TEST_METHOD = Pattern.compile(
        "@(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\\b");

    /** Объявление класса (для проверки на abstract). */
    private static final Pattern CLASS_DECLARATION = Pattern.compile(
        "\\b(?:class|interface|enum|record)\\s+(\\w+)");

    /** Модуль, из которого взяты бывшие сироты. */
    private static final String ENGINE_MODULE = "zorrobpm-engine";

    /**
     * Критерий WO-OPS-25 №4: в репозитории не должно остаться НИ ОДНОГО тестового класса,
     * который не покрыт ни одним include'ом (ни surefire, ни failsafe) своего модуля.
     */
    @Test
    void everyTestClassIsCoveredBySurefireOrFailsafeIncludes() {
        Path root = repoRoot();
        List<String> orphans = new ArrayList<>();

        for (Path module : modules(root)) {
            Path testRoot = module.resolve("src/test/java");
            if (!Files.isDirectory(testRoot)) {
                continue;
            }
            List<String> surefireIncludes = surefireIncludes(root, module);
            List<String> surefireExcludes = surefireExcludes(root, module);
            List<String> failsafeIncludes = failsafeIncludes(root, module);
            List<String> failsafeExcludes = failsafeExcludes(root, module);

            for (Path file : javaFiles(testRoot)) {
                String rel = testRoot.relativize(file).toString().replace('\\', '/');
                if (matchesAny(rel, surefireIncludes) && !matchesAny(rel, surefireExcludes)) {
                    continue;
                }
                if (matchesAny(rel, failsafeIncludes) && !matchesAny(rel, failsafeExcludes)) {
                    continue;
                }
                String source = stripCommentsAndLiterals(read(file));
                if (!TEST_METHOD.matcher(source).find()) {
                    continue; // хелпер/база/расширение — не тест-класс
                }
                if (!hasConcreteTypeDeclaration(source)) {
                    continue; // abstract-класс JUnit не инстанцирует
                }
                orphans.add(module.getFileName() + "/" + rel);
            }
        }

        assertThat(orphans)
            .as("Классы вне include'ов surefire/failsafe — пайплайн их НИКОГДА не запустит "
                + "(переименуй в *IntegrationTests/*PgIT/*IT либо добавь include в pom модуля)")
            .isEmpty();
    }

    /**
     * Регрессионная страховка на конкретную находку WO-OPS-25: восемь бывших сирот
     * переименованы в {@code *IntegrationTests} и потому покрыты failsafe-includes
     * engine-pom. Возврат старого имени {@code *IT} роняет этот тест.
     */
    @Test
    void formerOrphanRenames_stayCoveredByFailsafeIncludes() {
        Path root = repoRoot();
        Path engine = root.resolve(ENGINE_MODULE);
        Path testRoot = engine.resolve("src/test/java");
        List<String> includes = failsafeIncludes(root, engine);
        List<String> excludes = failsafeExcludes(root, engine);

        assertThat(FORMER_ORPHANS_RENAMED)
            .as("список бывших сирот не должен пустовать — иначе тест ни о чём не говорит")
            .isNotEmpty();

        List<String> uncovered = FORMER_ORPHANS_RENAMED.stream()
            .filter(rel -> !matchesAny(rel, includes) || matchesAny(rel, excludes))
            .toList();

        assertThat(uncovered)
            .as("бывшие сироты обязаны быть покрыты failsafe-includes модуля " + ENGINE_MODULE)
            .isEmpty();

        for (String rel : FORMER_ORPHANS_RENAMED) {
            assertThat(Files.exists(testRoot.resolve(rel)))
                .as("файл бывшей сироты должен существовать: " + rel)
                .isTrue();
        }
    }

    // ------------------------------------------------------------------ модули и poms

    /** Все модули репозитория: каталоги верхнего уровня с {@code pom.xml}. */
    private static List<Path> modules(Path root) {
        try (Stream<Path> children = Files.list(root)) {
            return children
                .filter(Files::isDirectory)
                .filter(p -> Files.exists(p.resolve("pom.xml")))
                .sorted()
                .toList();
        } catch (IOException e) {
            throw new IllegalStateException("не удалось прочитать корень репозитория " + root, e);
        }
    }

    /**
     * Эффективные include'ы surefire модуля: собственная конфигурация модуля, иначе —
     * унаследованная от корневого pom (pluginManagement → build/plugins), иначе —
     * дефолты Maven.
     */
    private static List<String> surefireIncludes(Path root, Path module) {
        Element plugin = pluginConfig(root, module, "maven-surefire-plugin");
        List<String> includes = plugin == null ? List.of() : includesOf(plugin);
        return includes.isEmpty() ? SUREFIRE_DEFAULT_INCLUDES : includes;
    }

    private static List<String> surefireExcludes(Path root, Path module) {
        Element plugin = pluginConfig(root, module, "maven-surefire-plugin");
        return plugin == null ? List.of() : excludesOf(plugin);
    }

    /**
     * Эффективные include'ы failsafe модуля. Если модуль не объявил failsafe с
     * execution'ом {@code integration-test} — интеграционной фазы у него нет, и любой
     * класс вне surefire/include'ов является сиротой.
     */
    private static List<String> failsafeIncludes(Path root, Path module) {
        Element plugin = pluginConfig(root, module, "maven-failsafe-plugin");
        if (plugin == null || !hasIntegrationTestExecution(plugin)) {
            return List.of();
        }
        List<String> includes = includesOf(plugin);
        return includes.isEmpty() ? FAILSAFE_DEFAULT_INCLUDES : includes;
    }

    private static List<String> failsafeExcludes(Path root, Path module) {
        Element plugin = pluginConfig(root, module, "maven-failsafe-plugin");
        if (plugin == null || !hasIntegrationTestExecution(plugin)) {
            return List.of();
        }
        return excludesOf(plugin);
    }

    /**
     * Конфигурация плагина для модуля: собственная — приоритетна; иначе pluginManagement
     * корневого pom; иначе build/plugins корневого pom.
     */
    private static Element pluginConfig(Path root, Path module, String artifactId) {
        Element own = findPlugin(buildPlugins(parse(module.resolve("pom.xml"))), artifactId);
        if (own != null) {
            return own;
        }
        Element rootDoc = parse(root.resolve("pom.xml"));
        Element managed = findPlugin(child(child(rootDoc, "build"), "pluginManagement"), artifactId);
        if (managed != null) {
            return managed;
        }
        return findPlugin(buildPlugins(rootDoc), artifactId);
    }

    /** {@code <build><plugins>} корневого элемента pom. */
    private static Element buildPlugins(Element projectRoot) {
        return child(child(projectRoot, "build"), "plugins");
    }

    private static Element findPlugin(Element container, String artifactId) {
        if (container == null) {
            return null;
        }
        for (Element plugin : children(container, "plugin")) {
            Element aid = child(plugin, "artifactId");
            if (aid != null && artifactId.equals(aid.getTextContent().trim())) {
                return plugin;
            }
        }
        return null;
    }

    private static boolean hasIntegrationTestExecution(Element plugin) {
        Element executions = child(plugin, "executions");
        if (executions == null) {
            return false;
        }
        for (Element execution : children(executions, "execution")) {
            Element goals = child(execution, "goals");
            if (goals == null) {
                continue;
            }
            for (Element goal : children(goals, "goal")) {
                String g = goal.getTextContent().trim();
                if ("integration-test".equals(g) || "verify".equals(g)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> includesOf(Element plugin) {
        return patternValues(child(plugin, "configuration"), "includes", "include");
    }

    private static List<String> excludesOf(Element plugin) {
        return patternValues(child(plugin, "configuration"), "excludes", "exclude");
    }

    /**
     * Значение параметра-массива Maven в ОБЕИХ допустимых формах:
     * {@code <includes><include>a</include><include>b</include></includes>} и
     * {@code <excludes>a</excludes>} (String[] — при нескольких значениях через запятую).
     * Вторая форма реально используется в engine-pom
     * ({@code <excludes>**}{@code /}{@code *IntegrationTests.java</excludes>}) — её легко не заметить
     * и тогда посчитать покрытым класс, который на самом деле в surefire НЕ попадает.
     */
    private static List<String> patternValues(Element configuration, String name, String child) {
        if (configuration == null) {
            return List.of();
        }
        Element element = child(configuration, name);
        if (element == null) {
            return List.of();
        }
        List<String> nested = texts(children(element, child));
        if (!nested.isEmpty()) {
            return nested;
        }
        String text = element.getTextContent() == null ? "" : element.getTextContent().trim();
        if (text.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String part : text.split(",")) {
            String value = part.trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }

    // ------------------------------------------------------------------ pom DOM

    private static Element parse(Path pom) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            Document document = factory.newDocumentBuilder().parse(pom.toFile());
            return document.getDocumentElement();
        } catch (Exception e) {
            throw new IllegalStateException("не удалось разобрать pom " + pom, e);
        }
    }

    private static Element child(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && name.equals(node.getNodeName())) {
                return (Element) node;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        if (parent == null) {
            return result;
        }
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && name.equals(node.getNodeName())) {
                result.add((Element) node);
            }
        }
        return result;
    }

    private static List<String> texts(List<Element> elements) {
        List<String> values = new ArrayList<>();
        for (Element element : elements) {
            String text = element.getTextContent().trim();
            if (!text.isEmpty()) {
                values.add(text);
            }
        }
        return values;
    }

    // ------------------------------------------------------------------ сопоставление паттернов

    /**
     * Паттерн Maven → регулярка: {@code **}{@code /} любой (в т.ч. пустой) путь, {@code *} — любые
     * символы кроме {@code /}, {@code ?} — один символ кроме {@code /}.
     */
    private static Pattern toRegex(String pattern) {
        StringBuilder out = new StringBuilder("^");
        int i = 0;
        while (i < pattern.length()) {
            if (pattern.startsWith("**/", i)) {
                out.append("(?:.*/)?");
                i += 3;
            } else if (pattern.charAt(i) == '*') {
                out.append("[^/]*");
                i++;
            } else if (pattern.charAt(i) == '?') {
                out.append("[^/]");
                i++;
            } else {
                out.append(Pattern.quote(String.valueOf(pattern.charAt(i))));
                i++;
            }
        }
        return Pattern.compile(out.append("$").toString());
    }

    private static boolean matchesAny(String relativePath, List<String> patterns) {
        for (String pattern : patterns) {
            if (toRegex(pattern).matcher(relativePath).matches()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ исходники

    private static List<Path> javaFiles(Path testRoot) {
        try (Stream<Path> files = Files.walk(testRoot)) {
            return files.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .sorted()
                .toList();
        } catch (IOException e) {
            throw new IllegalStateException("не удалось обойти " + testRoot, e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("не удалось прочитать " + file, e);
        }
    }

    /**
     * Есть ли в файле НЕ-абстрактное объявление типа: JUnit инстанцирует только
     * конкретные классы, {@code abstract}-база (с общим для наследников @Test-методом)
     * отдельным тестом не является.
     */
    private static boolean hasConcreteTypeDeclaration(String source) {
        var matcher = CLASS_DECLARATION.matcher(source);
        while (matcher.find()) {
            String head = source.substring(Math.max(0, matcher.start() - 80), matcher.start());
            if (!head.matches("(?s).*\\babstract\\s+(?:final\\s+)?$")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Вырезает комментарии и строковые литералы — иначе упоминание {@code @Test} в
     * javadoc или в строке дало бы ложное «это тест-класс».
     */
    static String stripCommentsAndLiterals(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(n, i + 2);
            } else if (c == '"') {
                if (source.startsWith("\"\"\"", i)) {
                    int end = source.indexOf("\"\"\"", i + 3);
                    i = end < 0 ? n : end + 3;
                } else {
                    i++;
                    while (i < n && source.charAt(i) != '"') {
                        if (source.charAt(i) == '\\') {
                            i++;
                        }
                        i++;
                    }
                    i = Math.min(n, i + 1);
                }
            } else if (c == '\'') {
                i++;
                while (i < n && source.charAt(i) != '\'') {
                    if (source.charAt(i) == '\\') {
                        i++;
                    }
                    i++;
                }
                i = Math.min(n, i + 1);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * Корень репозитория по МАРКЕРАМ (тот же приём, что в
     * {@link DeployCopyListGuardTest#repoRoot()}): тест гоняется из каталога модуля,
     * «первый pom.xml вверху» — сам модуль.
     */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("pom.xml"))
                && Files.exists(dir.resolve("docker-compose.yml"))
                && Files.isDirectory(dir.resolve("ci"))
                && Files.isDirectory(dir.resolve(ENGINE_MODULE))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("корень репозитория не найден (cwd=" + Path.of("") + ")");
    }
}
