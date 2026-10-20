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
            Element modulePom = parse(module.resolve("pom.xml"));
            Element rootPom = parse(root.resolve("pom.xml"));
            List<String> surefireIncludes = surefireIncludes(modulePom, rootPom);
            List<String> surefireExcludes = surefireExcludes(modulePom, rootPom);
            List<String> failsafeIncludes = failsafeIncludes(modulePom, rootPom);
            List<String> failsafeExcludes = failsafeExcludes(modulePom, rootPom);

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
        List<String> includes = failsafeIncludes(parse(engine.resolve("pom.xml")), parse(root.resolve("pom.xml")));
        List<String> excludes = failsafeExcludes(parse(engine.resolve("pom.xml")), parse(root.resolve("pom.xml")));

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

    /**
     * Регрессия на латентный дефект разрешения плагинов (находка независимой проверки
     * раунда 1, оформительская): {@code <pluginManagement>} САМ ПО СЕБЕ не активирует
     * failsafe — интеграционная фаза появляется только при объявлении плагина в
     * {@code <build><plugins>}. Если бы разрешение шло «pluginManagement → build/plugins»,
     * гард считал бы, что у модуля есть интеграционная фаза, и МОЛЧАЛ бы о классах,
     * которые пайплайн никогда не запустит — то есть тихо перестал бы ловить свой же
     * дефект.
     */
    @Test
    void pluginManagementAlone_doesNotActivateFailsafePhase() {
        Element rootWithManagedOnly = parseString("""
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>g</groupId><artifactId>root</artifactId><version>1</version>
              <build>
                <pluginManagement>
                  <plugins>
                    <plugin>
                      <artifactId>maven-failsafe-plugin</artifactId>
                      <executions>
                        <execution>
                          <goals><goal>integration-test</goal><goal>verify</goal></goals>
                        </execution>
                      </executions>
                      <configuration>
                        <includes><include>**/*IntegrationTests.java</include></includes>
                      </configuration>
                    </plugin>
                  </plugins>
                </pluginManagement>
              </build>
            </project>
            """);
        Element moduleWithoutFailsafe = parseString("""
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>g</groupId><artifactId>mod</artifactId><version>1</version>
            </project>
            """);

        assertThat(hasFailsafePhase(moduleWithoutFailsafe, rootWithManagedOnly))
            .as("failsafe, объявленный ТОЛЬКО в pluginManagement, не создаёт интеграционную фазу")
            .isFalse();
        assertThat(failsafeIncludes(moduleWithoutFailsafe, rootWithManagedOnly))
            .as("без активированного failsafe include'ов быть не может")
            .isEmpty();

        // Контроль: то же объявление в build/plugins — фаза ЕСТЬ и include'ы применяются.
        Element rootWithBuildPlugins = parseString("""
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>g</groupId><artifactId>root</artifactId><version>1</version>
              <build>
                <plugins>
                  <plugin>
                    <artifactId>maven-failsafe-plugin</artifactId>
                    <executions>
                      <execution>
                        <goals><goal>integration-test</goal><goal>verify</goal></goals>
                      </execution>
                    </executions>
                    <configuration>
                      <includes><include>**/*IntegrationTests.java</include></includes>
                    </configuration>
                  </plugin>
                </plugins>
              </build>
            </project>
            """);

        assertThat(hasFailsafePhase(moduleWithoutFailsafe, rootWithBuildPlugins))
            .as("объявленный в build/plugins failsafe создаёт интеграционную фазу")
            .isTrue();
        assertThat(failsafeIncludes(moduleWithoutFailsafe, rootWithBuildPlugins))
            .containsExactly("**/*IntegrationTests.java");
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
     * унаследованная от корневого pom (build/plugins), иначе — pluginManagement корневого
     * pom, иначе — дефолты Maven. (Surefire входит в дефолтный жизненный цикл, поэтому
     * фаза {@code test} есть у модуля всегда, даже если плагин нигде не объявлен.)
     */
    private static List<String> surefireIncludes(Element modulePom, Element rootPom) {
        List<String> includes = includesOf(configChain(modulePom, rootPom, "maven-surefire-plugin"));
        return includes.isEmpty() ? SUREFIRE_DEFAULT_INCLUDES : includes;
    }

    private static List<String> surefireExcludes(Element modulePom, Element rootPom) {
        return excludesOf(configChain(modulePom, rootPom, "maven-surefire-plugin"));
    }

    /**
     * Эффективные include'ы failsafe модуля. Если модуль не объявил failsafe в
     * {@code <build><plugins>} — интеграционной фазы у него нет, и любой класс вне
     * surefire/include'ов является сиротой.
     */
    private static List<String> failsafeIncludes(Element modulePom, Element rootPom) {
        if (!hasFailsafePhase(modulePom, rootPom)) {
            return List.of();
        }
        List<String> includes = includesOf(configChain(modulePom, rootPom, "maven-failsafe-plugin"));
        return includes.isEmpty() ? FAILSAFE_DEFAULT_INCLUDES : includes;
    }

    private static List<String> failsafeExcludes(Element modulePom, Element rootPom) {
        if (!hasFailsafePhase(modulePom, rootPom)) {
            return List.of();
        }
        return excludesOf(configChain(modulePom, rootPom, "maven-failsafe-plugin"));
    }

    /**
     * Есть ли у модуля интеграционная фаза failsafe.
     *
     * <p>Failsafe НЕ входит в дефолтный жизненный цикл: фаза появляется только если
     * плагин ОБЪЯВЛЕН в {@code <build><plugins>} — собственном модуля или унаследованном
     * от корневого pom (root pom — parent всех модулей). Запись только в
     * {@code <pluginManagement>} фазу НЕ создаёт: pluginManagement даёт конфигурацию
     * плагину, который объявлен, и сам по себе ничего не активирует. Разрешение, при
     * котором pluginManagement проверялся РАНЬШЕ build/plugins, вводило в заблуждение:
     * гард решил бы, что у модуля есть интеграционная фаза, и промолчал бы о классах,
     * которые пайплайн не запустит.
     */
    static boolean hasFailsafePhase(Element modulePom, Element rootPom) {
        List<Element> activated = activatedPlugins(modulePom, rootPom, "maven-failsafe-plugin");
        if (activated.isEmpty()) {
            return false;
        }
        for (Element plugin : activated) {
            if (hasIntegrationTestExecution(plugin)) {
                return true;
            }
        }
        // executions могут прийти из pluginManagement — Maven их наследует объявленному плагину
        Element managed = managedPlugin(rootPom, "maven-failsafe-plugin");
        return managed != null && hasIntegrationTestExecution(managed);
    }

    /**
     * Плагины, ОБЪЯВЛЕННЫЕ в {@code <build><plugins>}: сначала собственные модуля, затем
     * унаследованные от корневого pom. Только такой плагин реально выполняется.
     */
    private static List<Element> activatedPlugins(Element modulePom, Element rootPom, String artifactId) {
        List<Element> plugins = new ArrayList<>();
        Element own = findPlugin(buildPlugins(modulePom), artifactId);
        if (own != null) {
            plugins.add(own);
        }
        Element inherited = findPlugin(buildPlugins(rootPom), artifactId);
        if (inherited != null && inherited != own) {
            plugins.add(inherited);
        }
        return plugins;
    }

    /**
     * Плагин из {@code <pluginManagement>} корневого pom — источник конфигурации и
     * executions по умолчанию, но НЕ признак того, что плагин активирован.
     *
     * <p>Структура Maven: {@code build/pluginManagement/plugins/plugin} — контейнером
     * для {@code <plugin>} является {@code <plugins>}, а не сам {@code <pluginManagement>}.
     */
    private static Element managedPlugin(Element rootPom, String artifactId) {
        Element pluginManagement = child(child(rootPom, "build"), "pluginManagement");
        return findPlugin(child(pluginManagement, "plugins"), artifactId);
    }

    /**
     * Плагины в порядке доминирования: объявленные в {@code <build><plugins>} (свои, потом
     * унаследованные) → {@code <pluginManagement>} корневого pom.
     */
    private static List<Element> configChain(Element modulePom, Element rootPom, String artifactId) {
        List<Element> chain = new ArrayList<>(activatedPlugins(modulePom, rootPom, artifactId));
        Element managed = managedPlugin(rootPom, artifactId);
        if (managed != null) {
            chain.add(managed);
        }
        return chain;
    }

    /** Первый непустой список include'ов в цепочке плагинов. */
    private static List<String> includesOf(List<Element> plugins) {
        for (Element plugin : plugins) {
            List<String> includes = patternValues(child(plugin, "configuration"), "includes", "include");
            if (!includes.isEmpty()) {
                return includes;
            }
        }
        return List.of();
    }

    /** Первый непустой список exclude'ов в цепочке плагинов. */
    private static List<String> excludesOf(List<Element> plugins) {
        for (Element plugin : plugins) {
            List<String> excludes = patternValues(child(plugin, "configuration"), "excludes", "exclude");
            if (!excludes.isEmpty()) {
                return excludes;
            }
        }
        return List.of();
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

    /** Разбор pom из строки — для синтетических pom'ов в тестах разрешения плагинов. */
    private static Element parseString(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            Document document = factory.newDocumentBuilder()
                .parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)));
            return document.getDocumentElement();
        } catch (Exception e) {
            throw new IllegalStateException("не удалось разобрать pom из строки", e);
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
