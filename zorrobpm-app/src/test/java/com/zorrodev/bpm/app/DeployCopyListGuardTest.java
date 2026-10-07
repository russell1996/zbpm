package com.zorrodev.bpm.app;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REL-65 (прод-инцидент 2026-10-07): сторож инварианта
 * «bind-mount-источники задеплоенных compose ⇔ список копирования deploy-джобы».
 *
 * <p><b>Дефект, который закрывается.</b> Первая выкатка после WO-QW-12 поднялась с
 * {@code docker-compose.yml}, монтирующим
 * {@code ./ci/rabbitmq/conf.d/30-management-path-prefix.conf}, а deploy-джоба
 * ({@code .gitlab-ci.yml}, блок «copy ONLY the runtime bundle») этот файл на хост не
 * копировала. Docker создал отсутствующий источник как <b>root-owned каталог</b>:
 * брокер остался без {@code management.path_prefix}, а приложение уже ходило в
 * Management API с суффиксом {@code /rabbitmq} → провижининг воркерских учёток 404.
 * Следующий деплой упал на шаге очистки ({@code Permission denied}), снеся перед
 * падением compose-файлы и {@code ci/observability/} вместе со scrape-token
 * (резервная копия снесена {@code trap … rm -f}).
 *
 * <p>Правило выводится из признаков в самих файлах (какие compose задеплоены, какие
 * {@code ./…} они монтируют, какие {@code cp -a} есть в deploy-блоке), поэтому новый
 * compose/bind-mount проверяется сам, без правки теста — тот же приём, что в
 * {@link ModuleConfigImportGuardTest} и {@link RabbitMqMgmtPathPrefixWiringGuardTest}.
 * Тест парсит НАСТОЯЩИЕ файлы на диске (G-N: никакой копии логики в теле теста).
 */
class DeployCopyListGuardTest {

    /** Compose-файлы, которые deploy-джоба реально везёт на хост (ожидаемый состав). */
    private static final Set<String> EXPECTED_DEPLOYED_COMPOSES = Set.of(
        "docker-compose.yml",
        "docker-compose.observability.yml");

    /**
     * Живой секрет хоста, который bind-mount'ится, но СОЗНАТЕЛЬНО не копируется:
     * никогда не коммитится ({@code .gitignore}), генерируется один раз на хосте и
     * переживает деплой механикой бэкап/restore в deploy-блоке. Единственный carve-out
     * прямого направления — держать его здесь поименно, а не «всего, чего нет в списке».
     */
    private static final String LIVE_SECRET_CARVE_OUT = "ci/observability/scrape-token";

    /** Короткая форма bind-mount'а: {@code - ./источник:/цель[:ro]}. */
    private static final Pattern SHORT_BIND_MOUNT = Pattern.compile(
        "-\\s+\\./([^:\\s]+)\\s*:");

    /** Токен вида {@code docker-compose*.yml} внутри deploy-блока. */
    private static final Pattern COMPOSE_FILE_TOKEN = Pattern.compile(
        "docker-compose\\S*\\.yml");

    /** Маркерная рамка извлекаемых блоков deploy-джобы (тот же текст гоняет harness). */
    private static final String PRE_WIPE_BEGIN = "WO-REL-65 BEGIN pre-wipe-sources";
    private static final String PRE_WIPE_END = "WO-REL-65 END pre-wipe-sources";

    /**
     * Состав задеплоенного сета должен меняться сознательно: добавление compose в
     * deploy ({@code -f …} или {@code cp -a …}) без обновления этого списка роняет
     * тест с указанием, что делать (инцидент начался именно с «забыли довезти файл»).
     */
    @Test
    void deployedComposeSetChangesOnlyConsciously() throws IOException {
        String deployBlock = deployBlock(read(".gitlab-ci.yml"));

        Set<String> referenced = new TreeSet<>();
        Matcher m = COMPOSE_FILE_TOKEN.matcher(deployBlock);
        while (m.find()) referenced.add(m.group());

        assertThat(referenced)
            .as("compose files referenced by the deploy job must stay exactly " + EXPECTED_DEPLOYED_COMPOSES
                + " — adding another compose to `up -f`/copy lines deploys it to prod and its "
                + "bind-mount sources then MUST be shipped too (extend EXPECTED_DEPLOYED_COMPOSES "
                + "and the copy list together, WO-REL-65). Found: " + referenced)
            .isEqualTo(new TreeSet<>(EXPECTED_DEPLOYED_COMPOSES));
    }

    /**
     * Прямое направление: каждый {@code ./…} bind-mount-источник задеплоенного compose
     * обязан либо копироваться deploy-джобой, либо быть поименованным carve-out'ом.
     * Краснеет на состоянии до хотфикса (нет {@code cp -a …30-management-path-prefix.conf}).
     */
    @Test
    void everyDeployedBindMountSourceIsShippedOrCarvedOut() throws IOException {
        Set<String> copySources = copySources(read(".gitlab-ci.yml"));
        assertThat(copySources)
            .as("the deploy block must ship SOMETHING, otherwise this test guards nothing "
                + "(a renamed `cp` or a moved block would go silent)")
            .isNotEmpty();

        List<String> offenders = new ArrayList<>();
        for (String compose : EXPECTED_DEPLOYED_COMPOSES) {
            String text = read(compose);
            assertThat(text).as(compose + " must exist").isNotNull();
            assertThat(text)
                .as(compose + " must not use long-form `type: bind` mounts — the parser below "
                    + "only understands the short `- ./src:/dst` form, and a mount it cannot see "
                    + "would silently escape the guard (extend SHORT_BIND_MOUNT instead of ignoring)")
                .doesNotContain("type: bind");
            for (String source : bindMountSources(text)) {
                if (!copySources.contains(source) && !LIVE_SECRET_CARVE_OUT.equals(source)) {
                    offenders.add(compose + ": bind-mount source ./" + source
                        + " is NOT copied by the deploy job (and is not the carved-out live secret) — "
                        + "Docker will create it on the host as a root-owned DIRECTORY (WO-REL-65 incident)");
                }
            }
        }

        assertThat(offenders)
            .as("every deployed bind-mount source must be shipped by the deploy job")
            .isEmpty();
    }

    /**
     * Незадеплоенные compose (soak-риг, e2e-override, CI-стенды) читаются тоже: их
     * источники НЕ обязаны копироваться, но файл обязан оставаться ВНЕ deploy-блока —
     * иначе его mount'ы молча становятся обязательными к доставке (см. тест выше).
     */
    @Test
    void nonDeployedComposesStayOutOfTheDeployJob() throws IOException {
        String deployBlock = deployBlock(read(".gitlab-ci.yml"));
        // Soak-риг (свой брокер, на прод не едет), e2e-override (вообще без volumes),
        // CI-стенды rabbit/pg: их mount'и везти не надо — но только пока они ВНЕ deploy.
        for (String rel : List.of(
            "docker-compose.multi.yml",
            "ci/docker-compose.e2e.yml",
            "ci/docker-compose.rabbit.yml",
            "ci/docker-compose.pg.yml")) {
            String base = Path.of(rel).getFileName().toString();
            assertThat(deployBlock)
                .as(rel + " is not deployed to prod — if the deploy job starts referencing it, "
                    + "its bind-mount sources MUST be shipped too (see everyDeployedBindMountSourceIsShippedOrCarvedOut)")
                .doesNotContain(base);
        }
    }

    /**
     * Обратное направление: в списке копирования не должно быть лишнего без причины.
     * Разрешён источник — bind-mount задеплоенного compose или сам compose-файл
     * ({@code up -f} читает его на хосте).
     */
    @Test
    void noUnjustifiedExtraCopies() throws IOException {
        Set<String> copySources = copySources(read(".gitlab-ci.yml"));

        Set<String> justified = new TreeSet<>(EXPECTED_DEPLOYED_COMPOSES);
        for (String compose : EXPECTED_DEPLOYED_COMPOSES) {
            justified.addAll(bindMountSources(read(compose)));
        }

        List<String> offenders = new ArrayList<>();
        for (String src : copySources) {
            if (!justified.contains(src)) {
                offenders.add("deploy copies '" + src + "', which is neither a deployed bind-mount "
                    + "source nor a deployed compose file — remove it or justify it in this test");
            }
        }
        assertThat(offenders).isEmpty();
    }

    /**
     * Критерий 3, первая половина: каждый источник из списка копирования покрыт
     * pre-wipe проверкой существования в чекауте — падение {@code cp} ПОСЛЕ wipe
     * оставляло бы хост без compose-файлов (инцидент 2026-10-07).
     */
    @Test
    void copySourcesAreGuardedByPreWipeCheck() throws IOException {
        String ci = read(".gitlab-ci.yml");
        Set<String> copySources = copySources(ci);
        Set<String> guarded = preWipeSources(ci);

        assertThat(guarded)
            .as("the pre-wipe existence check must list SOMETHING, otherwise it guards nothing")
            .isNotEmpty();
        List<String> offenders = new ArrayList<>();
        for (String src : copySources) {
            if (!guarded.contains(src)) {
                offenders.add("'" + src + "' is copied by the deploy job but missing from the "
                    + "pre-wipe existence check (" + PRE_WIPE_BEGIN + ") — a failed copy after "
                    + "the wipe would strand the host without compose files");
            }
        }
        assertThat(offenders).isEmpty();
    }

    /**
     * Критерий 3, вторая половина: бэкап scrape-token переживает падение деплоя.
     * Старая форма {@code trap 'rm -f "$SCRAPE_TOKEN_BAK"' EXIT} сносила бэкап при
     * ЛЮБОМ exit — вместе с уже снесённым DEPLOY_DIR хост оставался и без compose,
     * и без токена. Требуемая механика: именованная cleanup-функция, которая на пути
     * падения (бэкап сохранён, restore не выполнен) файл ОСТАВЛЯЕТ с указанием пути,
     * пустой mktemp-файл удаляет, код выхода пробрасывает, а после успешного restore
     * trap снимается ({@code trap - EXIT} после {@code mv}).
     */
    @Test
    void scrapeTokenBackupSurvivesDeployFailure() throws IOException {
        String deployBlock = deployBlock(read(".gitlab-ci.yml"));

        assertThat(deployBlock)
            .as("the backup trap must call a named cleanup function, not an inline `rm -f` — "
                + "an unconditional remove deletes the backup on the failure path too (WO-REL-65)")
            .contains("trap scrape_token_cleanup EXIT");
        assertThat(deployBlock).doesNotContain("trap 'rm -f \"$SCRAPE_TOKEN_BAK\"'");

        String function = cleanupFunction(deployBlock);
        assertThat(function)
            .as("scrape_token_cleanup must be defined in the deploy block (the harness extracts "
                + "and runs this exact text, G-N)")
            .isNotEmpty();
        // Путь падения (сохранён, но не восстановлен) — echo с путём, БЕЗ rm; путь
        // «нечего хранить» (mktemp-пустышка) — rm. Разрезаем по `else` и требуем
        // отсутствие rm в первой половине: иначе «preserved» на словах, снос на деле.
        String[] branches = function.split("; else ");
        assertThat(branches)
            .as("cleanup function must have a then/else shape (preserve on failure, drop the "
                + "empty placeholder otherwise): " + function)
            .hasSize(2);
        assertThat(branches[0])
            .as("failure branch must mention the saved-but-not-restored state and print the path, "
                + "not delete the file")
            .contains("\"$SCRAPE_TOKEN_SAVED\" = \"1\"")
            .contains("preserved");
        assertThat(branches[0]).doesNotContain("rm -f");
        assertThat(branches[1]).contains("rm -f \"$SCRAPE_TOKEN_BAK\"");
        assertThat(function)
            .as("cleanup must propagate the failing exit code, not mask it with 0")
            .contains("exit \"$rc\"");

        int mvPos = deployBlock.indexOf("mv \"$SCRAPE_TOKEN_BAK\"");
        int disarmPos = deployBlock.indexOf("trap - EXIT");
        assertThat(mvPos).as("restore must move the backup back").isGreaterThanOrEqualTo(0);
        assertThat(disarmPos)
            .as("the trap must be disarmed only AFTER the successful restore (trap - EXIT below mv)")
            .isGreaterThan(mvPos);
    }

    /**
     * Критерий 2, вторая половина: очистка переживает root-owned остатки bind-mount'ов,
     * которые Docker создаёт-как-каталог при отсутствующем источнике. Только
     * {@code sudo rm -rf} снимает такие; голый {@code rm -rf} умирает на середине с
     * полуснесённым DEPLOY_DIR (джоба 558464). Исключения wipe ({@code .env},
     * теги отката, бэкапы) при этом на месте.
     */
    @Test
    void wipeSurvivesRootOwnedLeftovers() throws IOException {
        String deployBlock = deployBlock(read(".gitlab-ci.yml"));
        String wipe = wipeLine(deployBlock);
        assertThat(wipe)
            .as("the DEPLOY_DIR wipe must exist in the deploy block")
            .isNotEmpty();
        assertThat(wipe)
            .as("the wipe must run under sudo — Docker auto-creates a missing bind-mount source "
                + "as a ROOT-owned directory, and a plain `rm -rf` aborts the wipe half-way (WO-REL-65)")
            .contains("sudo rm -rf");
        assertThat(wipe)
            .as("the wipe must keep excluding host state (.env, rollback tags, backups)")
            .contains("! -name .env")
            .contains(".current_tag")
            .contains(".previous_tag")
            .contains("backups");
    }

    // --- парсеры НАСТОЯЩИХ файлов (G-N: никакой копии логики в тесте) ------------

    /** Deploy-блок: от строки `deploy:` до следующей джобы того же уровня. */
    private static String deployBlock(String ciYaml) {
        String[] lines = ciYaml.split("\n");
        StringBuilder block = new StringBuilder();
        boolean inside = false;
        for (String line : lines) {
            if (line.matches("deploy:\\s*")) {
                inside = true;
                continue;
            }
            if (inside && line.matches("[a-zA-Z0-9_-]+:\\s*")) break;
            if (inside) block.append(line).append('\n');
        }
        assertThat(block.toString())
            .as("deploy: job block must exist in .gitlab-ci.yml")
            .isNotEmpty();
        return block.toString();
    }

    /** Источники всех `cp -a <src…> <dst>` строк deploy-блока (цели с `$` отбрасываются). */
    private static Set<String> copySources(String ciYaml) {
        Set<String> sources = new LinkedHashSet<>();
        Matcher line = Pattern.compile("(?m)^\\s*-\\s*cp -a\\s+(.*)$").matcher(deployBlock(ciYaml));
        while (line.find()) {
            for (String token : line.group(1).trim().split("\\s+")) {
                if (token.startsWith("$") || token.startsWith("\"$")) continue;
                sources.add(token.replaceAll("/+$", ""));
            }
        }
        return sources;
    }

    /** `./…` источники коротких bind-mount'ов compose-текста. */
    private static Set<String> bindMountSources(String composeText) {
        Set<String> sources = new LinkedHashSet<>();
        Matcher m = SHORT_BIND_MOUNT.matcher(composeText);
        while (m.find()) sources.add(m.group(1).replaceAll("/+$", ""));
        return sources;
    }

    /** Список `for src in …` между маркерами pre-wipe-проверки. */
    private static Set<String> preWipeSources(String ciYaml) {
        int begin = ciYaml.indexOf(PRE_WIPE_BEGIN);
        int end = ciYaml.indexOf(PRE_WIPE_END);
        assertThat(begin)
            .as("pre-wipe check marker '" + PRE_WIPE_BEGIN + "' must exist in .gitlab-ci.yml")
            .isGreaterThanOrEqualTo(0);
        assertThat(end).isGreaterThan(begin);
        String block = ciYaml.substring(begin, end);
        Matcher m = Pattern.compile("for src in (.*?);\\s*do").matcher(block);
        assertThat(m.find())
            .as("pre-wipe check must iterate `for src in …; do` (the harness runs this exact text)")
            .isTrue();
        Set<String> sources = new LinkedHashSet<>();
        for (String token : m.group(1).trim().split("\\s+")) {
            sources.add(token.replaceAll("/+$", ""));
        }
        return sources;
    }

    /** Строка определения scrape_token_cleanup (однострочная функция в deploy-блоке). */
    private static String cleanupFunction(String deployBlock) {
        Matcher m = Pattern.compile(
            "(?m)^\\s*-\\s*scrape_token_cleanup\\(\\) \\{.*\\}\\s*$").matcher(deployBlock);
        return m.find() ? m.group().trim().replaceAll("^-\\s*", "") : "";
    }

    /** Строка wipe (`find "$DEPLOY_DIR" …`). */
    private static String wipeLine(String deployBlock) {
        Matcher m = Pattern.compile(
            "(?m)^\\s*-\\s*find \"\\$DEPLOY_DIR\".*$").matcher(deployBlock);
        return m.find() ? m.group().trim() : "";
    }

    private static String read(String relative) throws IOException {
        Path path = repoRoot().resolve(relative);
        return Files.exists(path) ? Files.readString(path) : null;
    }

    /**
     * Корень репозитория по МАРКЕРАМ (тот же приём, что в
     * {@link RabbitMqMgmtPathPrefixWiringGuardTest#repoRoot()}): тест гоняется из
     * каталога модуля, «первый pom.xml вверху» — сам модуль.
     */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("pom.xml"))
                && Files.exists(dir.resolve("docker-compose.yml"))
                && Files.isDirectory(dir.resolve("ci"))
                && Files.isDirectory(dir.resolve("zorrobpm-engine"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("корень репозитория не найден (cwd=" + Path.of("") + ")");
    }
}
