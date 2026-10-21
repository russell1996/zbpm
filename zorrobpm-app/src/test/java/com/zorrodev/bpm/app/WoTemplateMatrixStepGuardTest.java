package com.zorrodev.bpm.app;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-OPS-30 Ф3 — сторож шага «матрица 8.9 | 8.5 live | ours» в шаблоне WO.
 *
 * <p><b>Что закрывает.</b> Правило G-Q в {@code CONTRIBUTING.md} требует, чтобы любая WO, меняющая
 * FEEL-вычисление, input/output mapping, обработку job или код воркера/коннектора, доказывала
 * паритет с Camunda 8 <b>тестом</b>, а не чтением. Механическая часть этого требования живёт в
 * WO-OPS-31 (строка {@code FEEL-PARITY:} в отчёте). Но отчёт — это конец пути: чтобы строка
 * матрицы вообще появилась, шаблон WO должен требовать её <b>до</b> начала работы. До этого шага
 * не было — и WO, взятая по шаблону, не несла в себе требования собрать матрицу, поэтому паритет
 * всплывал на финальном ревью, когда менять уже поздно.
 *
 * <p>Шаблон живёт в {@code .tooling/agents/implementer.md} (шаг 0 — как пишется
 * self-issued WO). Проверка читает РЕАЛЬНЫЙ файл на диске (G-N: никакой копии его содержимого в
 * теле теста) и требует, чтобы в нём был шаг про матрицу с тремя колонками и ссылкой на
 * оракульный раннер. Файл лежит в корне репозитория и НЕ исключён из контекста сборки
 * ({@code .dockerignore}), поэтому проверка работает и в {@code docker build --target test}
 * (job {@code test:backend}) — ровно там же, где работают {@code ModuleConfigImportGuardTest} и
 * {@code DeployCopyListGuardTest}.
 *
 * <p>Почему это не «пустая» проверка текста: правило без механической привязки — это ровно тот
 * класс находок, что стоили проекту десятки лишних раундов ревью (см. P-64 в
 * {@code docs/internal}: «каждый гейт, который извлекает факт из ИСПОЛНЯЕМОГО скрипта
 * текстовым совпадением… теряет строгость молча»). Здесь обратная сторона: правило, которое
 * существует только прозой, выветривается молча — и заметить это можно лишь на ревью.
 */
class WoTemplateMatrixStepGuardTest {

    /** Шаблон self-issued WO: шаг 0 ролевого промпта исполнителя. */
    private static final Path TEMPLATE = Path.of(".tooling", "agents", "implementer.md");

    /** Канонический текст CONTRIBUTING.md про G-Q — источник требования, на которое ссылается шаг. */
    private static final Path AGENTS = Path.of("CONTRIBUTING.md");

    @Test
    void woTemplateRequiresTheThreeColumnMatrixStep() throws IOException {
        Path root = repoRoot();
        Path template = root.resolve(TEMPLATE);
        assertThat(Files.isRegularFile(template))
            .as("шаблон WO (шаг 0 ролевого промпта) доступен: %s", TEMPLATE)
            .isTrue();

        String text = Files.readString(template, StandardCharsets.UTF_8);

        // Шаг обязан называть САМ шаг, три колонки и оракульный раннер — иначе это не про матрицу.
        assertThat(text)
            .as("шаблон WO требует шаг «матрица 8.9 | 8.5 live | ours»")
            .contains("матрица 8.9 | 8.5 live | ours");

        assertThat(text)
            .as("шаблон WO перечисляет три колонки матрицы")
            .contains("8.9 docs")
            .contains("8.5 live")
            .contains("ours");

        assertThat(text)
            .as("шаблон WO указывает оракульный раннер (живой 8.5) и корпус кейсов")
            .contains("docs/analysis/c8-oracle/");
    }

    @Test
    void theMatrixStepNamesTheSubjectsThatRequireIt() throws IOException {
        String text = Files.readString(repoRoot().resolve(TEMPLATE), StandardCharsets.UTF_8);

        // Область применения шага — ровно та, что у G-Q в CONTRIBUTING.md. Если предметная часть
        // разъедется, шаг начнёт требовать матрицу от не-FEEL-задач (шум) или, наоборот,
        // пропустит FEEL-задачу (дыра) — оба случая ловятся здесь по предметному списку.
        List<String> subjects = List.of(
            "FEEL-вычисление",
            "input/output mapping",
            "обработку job",
            "код воркера");
        for (String subject : subjects) {
            assertThat(text)
                .as("шаг матрицы в шаблоне WO перечисляет предмет: %s", subject)
                .contains(subject);
        }
    }

    @Test
    void theMatrixStepRefersToTheRuleItImplements() throws IOException {
        Path root = repoRoot();
        assertThat(Files.isRegularFile(root.resolve(AGENTS)))
            .as("CONTRIBUTING.md доступен — на него ссылается шаг шаблона")
            .isTrue();

        String template = Files.readString(root.resolve(TEMPLATE), StandardCharsets.UTF_8);
        String agents = Files.readString(root.resolve(AGENTS), StandardCharsets.UTF_8);

        // Ссылка должна быть живой: если в CONTRIBUTING.md правило переименуют/уберут, шаг не должен
        // молча указывать в пустоту. Сверяем по существу, а не по номеру пункта (номер меняется).
        assertThat(template)
            .as("шаг матрицы в шаблоне ссылается на правило G-Q в CONTRIBUTING.md")
            .contains("G-Q");
        assertThat(agents)
            .as("в CONTRIBUTING.md есть правило G-Q (паритет FEEL/ioMapping/воркеров доказывается тестом)")
            .contains("G-Q")
            .contains("8.9 docs");
    }

    @Test
    void everySubjectOfTheStepIsAlsoInTheGateRule() throws IOException {
        // Сторож не должен требовать большего, чем требует канон: если бы в шаблоне появился
        // предмет, которого нет в G-Q, это была бы новая норма, заданная мимо канона.
        String template = Files.readString(repoRoot().resolve(TEMPLATE), StandardCharsets.UTF_8);
        String agents = Files.readString(repoRoot().resolve(AGENTS), StandardCharsets.UTF_8);

        List<String> subjects = List.of(
            "FEEL-вычисление",
            "input/output mapping",
            "обработку job",
            "код воркера");
        List<String> missing = new ArrayList<>();
        for (String subject : subjects) {
            // В CONTRIBUTING.md формулировки короче («FEEL-вычисление, input/output mapping, обработка
            // job … код воркера»), поэтому сверяем по смысловым словам, а не по точной строке.
            String probe = switch (subject) {
                case "FEEL-вычисление" -> "FEEL";
                case "input/output mapping" -> "mapping";
                case "обработку job" -> "job";
                default -> "воркер";
            };
            if (!agents.toLowerCase(Locale.ROOT).contains(probe.toLowerCase(Locale.ROOT))) {
                missing.add(subject);
            }
        }
        assertThat(missing)
            .as("все предметы шага матрицы присутствуют в правиле G-Q (шаг не вводит норму мимо канона)")
            .isEmpty();
    }

    /** Корень репозитория: ищем вверх по дереву (как в ModuleConfigImportGuardTest). */
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
