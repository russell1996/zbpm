package com.zorrodev.bpm.app;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REL-67 (инциденты 2026-10-08): сторож инварианта «ночной schedule-пайплайн
 * содержит ТОЛЬКО backup:pg, test:chaos, test:e2e-login».
 *
 * <p><b>Дефект, который закрывается.</b> Расписание №56 («nightly PG backup»)
 * запускало ВЕСЬ пайплайн: {@code backup:pg} — 12 с, а {@code build},
 * {@code test:backend} (25 мин), {@code test:pg} (10 мин), {@code test:frontend},
 * все CVE-сканы шли впридачу — те же коммиты уже прошли всё это на push.
 * Отдельно {@code backup:pg} терялся GitLab-авто-отменой (пайплайн 176380,
 * ночь 06→07.10 без дампа).
 *
 * <p>Правила матчат сверху вниз, первое совпадение побеждает: schedule-исключение
 * обязано стоять ПЕРВОЙ rules-записью каждой не-ночной джобы, иначе ветка
 * {@code $CI_COMMIT_BRANCH} (на schedule она ТОЖЕ установлена — расписание идёт
 * по ветке!) её перекроет. Новая джоба без schedule-решения роняет тест —
 * сознательно, тем же приёмом, что
 * {@code DeployCopyListGuardTest.deployedComposeSetChangesOnlyConsciously}.
 * Тест парсит НАСТОЯЩИЙ {@code .gitlab-ci.yml} на диске (G-N).
 */
class ScheduleSplitGuardTest {

    /** Джобы, которым на schedule быть положено (WO-REL-67, критерий 2). */
    private static final Set<String> SCHEDULE_JOBS = Set.of(
        "backup:pg", "test:chaos", "test:e2e-login");

    /** Не-джобы верхнего уровня (глобальные ключи и якоря, не пайплайн-джобы). */
    private static final Set<String> NON_JOB_KEYS = Set.of(
        "stages", "variables", "workflow", "default", "include", "spec");

    /** Заголовок джобы/ключа верхнего уровня: начинается с колонки 0 (имена вроде mirror:github содержат двоеточие). */
    private static final Pattern TOP_LEVEL_KEY = Pattern.compile(
        "(?m)^([A-Za-z][\\w:.-]*):\\s*(#.*)?$");

    @Test
    void schedulePipelineContainsOnlyBackupChaosAndE2e() {
        String yaml = read(".gitlab-ci.yml");
        assertThat(yaml).as(".gitlab-ci.yml must exist").isNotNull();

        Map<String, String> jobs = jobBlocks(yaml);
        assertThat(jobs).as("must parse at least the known jobs").isNotEmpty();

        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, String> job : jobs.entrySet()) {
            String name = job.getKey();
            String block = job.getValue();
            if (SCHEDULE_JOBS.contains(name)) {
                continue;
            }
            if ("mirror:github".equals(name)) {
                // push-only: правила требуют SOURCE == "push", schedule туда не попадает.
                if (!block.contains("== \"push\"") || block.contains("schedule")) {
                    offenders.add(name + ": mirror job must stay push-only "
                        + "(rules with == \"push\", no schedule entry)");
                }
                continue;
            }
            String rules = rulesBlock(yaml, block);
            if (rules == null) {
                offenders.add(name + ": no `rules:` block found — the guard cannot see "
                    + "this job (renamed/moved?), refusing to assume it is schedule-safe");
                continue;
            }
            if (!firstRuleIsScheduleNever(rules)) {
                offenders.add(name + ": first rules entry must be "
                    + "`$CI_PIPELINE_SOURCE == \"schedule\"` with `when: never` "
                    + "(rules match top-down; a branch condition below ALSO matches "
                    + "schedules, which run on a branch)");
            }
        }

        assertThat(offenders)
            .as("every job except " + SCHEDULE_JOBS + " must opt out of schedules; "
                + "a NEW job without a schedule decision must fail here consciously, "
                + "not ride the nightly pipeline silently")
            .isEmpty();
    }

    @Test
    void scheduledJobsStillRunOnSchedule() {
        String yaml = read(".gitlab-ci.yml");
        assertThat(yaml).as(".gitlab-ci.yml must exist").isNotNull();
        Map<String, String> jobs = jobBlocks(yaml);

        for (String name : SCHEDULE_JOBS) {
            assertThat(jobs)
                .as("scheduled job " + name + " must exist")
                .containsKey(name);
            String block = jobs.get(name);
            assertThat(block)
                .as(name + " must keep its `$CI_PIPELINE_SOURCE == \"schedule\"` rule")
                .contains("$CI_PIPELINE_SOURCE == \"schedule\"");
            String rules = rulesBlock(yaml, block);
            assertThat(rules)
                .as(name + " must not carry a schedule `when: never`")
                .doesNotContain("when: never");
        }
    }

    @Test
    void backupPgSurvivesAutoCancelAndGuardsFreshness() {
        String yaml = read(".gitlab-ci.yml");
        assertThat(yaml).as(".gitlab-ci.yml must exist").isNotNull();
        Map<String, String> jobs = jobBlocks(yaml);
        assertThat(jobs).as("backup:pg must exist").containsKey("backup:pg");
        String block = jobs.get("backup:pg");

        // Инцидент C (пайплайн 176380): авто-отмена не трогает interruptible: false.
        assertThat(block)
            .as("backup:pg must be `interruptible: false` (GitLab auto-cancel "
                + "never cancels such jobs — incident 2026-10-07, pipeline 176380)")
            .contains("interruptible: false");
        // Только schedule: ни веточных, ни MR-правил (трогает живой прод-контейнер).
        assertThat(block)
            .as("backup:pg must stay schedule-only")
            .doesNotContain("$CI_COMMIT_BRANCH");
        assertThat(block)
            .as("backup:pg must stay schedule-only (no MR trigger)")
            .doesNotContain("merge_request_event");
        // Сторож свежести идёт в той же джобе, после самого бэкапа.
        int backupStep = block.indexOf("bash ci/pg-backup.sh");
        int guardStep = block.indexOf("bash ci/check-backup-freshness.sh");
        assertThat(backupStep)
            .as("backup:pg must run ci/pg-backup.sh")
            .isNotEqualTo(-1);
        assertThat(guardStep)
            .as("backup:pg must run ci/check-backup-freshness.sh after the backup")
            .isGreaterThan(backupStep);
    }

    /**
     * Первая rules-запись — schedule-исключение: условие на
     * {@code $CI_PIPELINE_SOURCE == "schedule"} и в следующих строках (до
     * следующей {@code - if:} или конца блока) {@code when: never}.
     */
    private static boolean firstRuleIsScheduleNever(String rules) {
        String[] lines = rules.split("\n");
        int ifIdx = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].matches("\\s*-\\s*if:.*")) {
                ifIdx = i;
                break;
            }
        }
        if (ifIdx < 0) {
            return false;
        }
        if (!lines[ifIdx].contains("$CI_PIPELINE_SOURCE == \"schedule\"")) {
            return false;
        }
        for (int i = ifIdx + 1; i < lines.length; i++) {
            if (lines[i].matches("\\s*-\\s*if:.*")) {
                break;
            }
            if (lines[i].matches("\\s*when:\\s*never.*")) {
                return true;
            }
        }
        return false;
    }

    /** Блоки верхнего уровня, без глобальных ключей и якорей. */
    private static Map<String, String> jobBlocks(String yaml) {
        Map<String, String> blocks = new LinkedHashMap<>();
        Matcher m = TOP_LEVEL_KEY.matcher(yaml);
        List<String> names = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        while (m.find()) {
            names.add(m.group(1).trim());
            starts.add(m.start());
        }
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            if (NON_JOB_KEYS.contains(name) || name.startsWith(".")) {
                continue;
            }
            int end = (i + 1 < names.size()) ? starts.get(i + 1) : yaml.length();
            blocks.put(name, yaml.substring(starts.get(i), end));
        }
        // Fail-closed: состав джоб тоже под стражей — новая джоба видна здесь.
        assertThat(new TreeSet<>(blocks.keySet()))
            .as("pipeline job set changed — if a job was added/renamed, extend "
                + "SCHEDULE_JOBS or add its schedule opt-out consciously")
            .containsExactlyInAnyOrder(
                "backup:pg", "build", "cve:npm", "cve:report", "cve:scan",
                "deploy", "mirror:github", "rollback",
                "test:backend", "test:chaos", "test:e2e-login", "test:frontend",
                "test:gate", "test:pg", "test:rabbit");
        return blocks;
    }

    /** Текст после `rules:` внутри блока джобы; алиас `*anchor` резолвится в тело якоря. */
    private static String rulesBlock(String yaml, String block) {
        Matcher m = Pattern.compile("(?m)^\\s*rules:\\s*(\\*\\S+)?\\s*(#.*)?$").matcher(block);
        if (!m.find()) {
            return null;
        }
        if (m.group(1) != null) {
            // `rules: *rules-code-changes` — подставить тело якоря (там и живёт
            // schedule-исключение для всех семи джоб; молча пропустить алиас
            // значило бы не видеть половину пайплайна).
            String anchor = m.group(1).substring(1);
            Matcher am = Pattern.compile(
                "(?m)^\\." + Pattern.quote(anchor) + ":\\s*&\\S+\\s*$").matcher(yaml);
            assertThat(am.find())
                .as("anchor ." + anchor + " referenced by rules must be defined")
                .isTrue();
            int bodyStart = am.end();
            Matcher next = TOP_LEVEL_KEY.matcher(yaml);
            int bodyEnd = yaml.length();
            while (next.find()) {
                if (next.start() >= bodyStart) {
                    bodyEnd = next.start();
                    break;
                }
            }
            String rules = yaml.substring(bodyStart, bodyEnd);
            assertThat(rules.trim())
                .as("anchor ." + anchor + " must carry rules, otherwise the guard is blind")
                .isNotEmpty();
            return rules;
        }
        return block.substring(m.end());
    }

    private static String read(String relative) {
        Path path = repoRoot().resolve(relative);
        try {
            return Files.exists(path) ? Files.readString(path) : null;
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + path, e);
        }
    }

    /** Тот же приём, что в DeployCopyListGuardTest.repoRoot(): маркеры корня. */
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
