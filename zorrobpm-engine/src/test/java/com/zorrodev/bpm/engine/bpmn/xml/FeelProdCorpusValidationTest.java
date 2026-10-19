package com.zorrodev.bpm.engine.bpmn.xml;

import com.zorrodev.bpm.engine.bpmn.xml.FeelSyntaxValidator.FeelExpressionKind;
import com.zorrodev.bpm.engine.configuration.FeelBigDecimalNumberMapper;
import org.camunda.feel.api.FeelEngineApi;
import org.camunda.feel.api.FeelEngineBuilder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-56, criterion 1 (the corpus half): EVERY FEEL expression of the production definitions
 * (corpus {@code docs/internal} — 173 unique expressions from
 * the last version of each of the 24 prod definitions) must pass the deploy-time validation —
 * EXCEPT the two known single-quoted conditions of {@code outgoingLetterPreparationProcess}
 * ({@code =returnClass = 'minor'}, {@code =sendMethod = 'physical'}), which are the whole
 * reason this WO exists.
 *
 * <p>The test does NOT re-implement the validation: it feeds the raw corpus values (unescaped
 * from their XML form, as the parser would see them in the BPMN source) through the SAME
 * {@link FeelSyntaxValidator#parseError} entry point the model walk uses, with the kind each
 * section's runtime field maps to. Sections that hold names or worker types
 * (mi.inputElement, mi.outputCollection, misc.taskDefinition…) are not expressions and are
 * skipped by count, not by hope; the counts below are pinned so a corpus edit or a mapping
 * mistake cannot silently shrink the test.
 */
class FeelProdCorpusValidationTest {

    private static final Path CORPUS =
        Paths.get("..", "governance", "reports", "feel-corpus-prod-2026-10-15.md");

    private static final Pattern ENTRY = Pattern.compile("^- `(.*?)`\\s+←\\s*(.+)$");

    private final FeelSyntaxValidator validator = new FeelSyntaxValidator(
        FeelEngineBuilder.forJava().withCustomValueMapper(new FeelBigDecimalNumberMapper()).build());

    @Test
    void everyProductionExpressionIsValidExceptTheTwoKnownSingleQuotedConditions() throws IOException {
        Map<String, List<Entry>> corpus = parseCorpus();

        int total = corpus.values().stream().mapToInt(List::size).sum();
        assertThat(total)
            .as("the corpus file was read completely (173 unique expressions)")
            .isEqualTo(173);

        // sections that hold VARIABLE NAMES or worker types — not expressions at runtime
        int nameOrTypeEntries =
            corpus.getOrDefault("mi.inputElement", List.of()).size()
            + corpus.getOrDefault("mi.outputCollection", List.of()).size()
            + corpus.entrySet().stream()
                .filter(e -> e.getKey().startsWith("misc.taskDefinition"))
                .mapToInt(e -> e.getValue().size()).sum();
        assertThat(nameOrTypeEntries)
            .as("non-expression sections (mi.inputElement 8 + mi.outputCollection 7 + misc.taskDefinition 29)")
            .isEqualTo(44);

        // the one timer entry is an ISO duration (PT5M), not FEEL — the runtime never parses it
        long nonFeelTimers = corpus.getOrDefault("timer", List.of()).stream()
            .filter(e -> !e.value().startsWith("=")).count();
        assertThat(nonFeelTimers).isEqualTo(1);

        List<Failure> failures = new ArrayList<>();
        int validated = 0;
        for (Map.Entry<String, List<Entry>> section : corpus.entrySet()) {
            String name = section.getKey();
            for (Entry entry : section.getValue()) {
                FeelExpressionKind kind = kindFor(name, entry.value());
                if (kind == null) {
                    continue; // name / worker type / non-FEEL timer
                }
                validated++;
                Optional<String> error = validator.parseError(kind, entry.value());
                if (error.isPresent()) {
                    failures.add(new Failure(name, entry.value(), entry.sources(), error.get()));
                }
            }
        }
        assertThat(validated)
            .as("every expression-bearing corpus entry went through the validator "
                + "(173 − 44 names/types − 1 non-FEEL timer)")
            .isEqualTo(128);

        assertThat(failures)
            .as("the ONLY invalid production expressions are the two known single-quoted "
                + "conditions of outgoingLetterPreparationProcess (section | value | sources)")
            .extracting(f -> f.section() + " | " + f.value() + " | " + f.sources())
            .containsExactlyInAnyOrder(
                "condition | =returnClass = 'minor' | outgoingLetterPreparationProcess",
                "condition | =sendMethod = 'physical' | outgoingLetterPreparationProcess");

        for (Failure f : failures) {
            assertThat(f.parseError()).contains("'");
        }
    }

    // ─── corpus plumbing ──────────────────────────────────────────────────

    private static FeelExpressionKind kindFor(String section, String value) {
        return switch (section) {
            case "condition" -> FeelExpressionKind.FLOW_CONDITION;
            case "assignee", "completionCondition", "input.source", "output.source",
                 "mi.inputCollection", "mi.outputElement", "misc.correlationKey" ->
                FeelExpressionKind.FEEL_EXPRESSION;
            case "timer" -> value.startsWith("=") ? FeelExpressionKind.FEEL_EXPRESSION : null;
            default -> null;
        };
    }

    private Map<String, List<Entry>> parseCorpus() throws IOException {
        String text = Files.readString(CORPUS);
        Map<String, List<Entry>> sections = new LinkedHashMap<>();
        String current = null;
        for (String line : text.split("\n")) {
            if (line.startsWith("## ")) {
                current = line.substring(3).trim();
                sections.putIfAbsent(current, new ArrayList<>());
                continue;
            }
            Matcher m = ENTRY.matcher(line.strip());
            if (m.matches() && current != null) {
                sections.get(current).add(new Entry(unescape(m.group(1)), m.group(2).trim()));
            }
        }
        return sections;
    }

    /**
     * The corpus stores the values as they appear in XML source — unescape exactly like the XML
     * parser would: the named entities plus NUMERIC character references (the corpus's real
     * newlines are stored as {@code &#10;}; unescaping them to real newlines is what the runtime
     * model actually holds — a FEEL lexer treats real newlines as whitespace, the literal
     * 5-character text {@code &#10;} would not parse).
     */
    private static String unescape(String xml) {
        String named = xml
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'");
        return NUMERIC_REFERENCE
            .matcher(named)
            .replaceAll(m -> {
                String code = m.group(1);
                int value = code.startsWith("x") || code.startsWith("X")
                    ? Integer.parseInt(code.substring(1), 16)
                    : Integer.parseInt(code);
                return String.valueOf((char) value);
            })
            .replace("&amp;", "&");
    }

    private static final Pattern NUMERIC_REFERENCE = Pattern.compile("&#(x?[0-9a-fA-F]+);");

    private record Entry(String value, String sources) {
    }

    private record Failure(String section, String value, String sources, String parseError) {
    }
}
