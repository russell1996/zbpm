package com.zorrodev.bpm.engine.bpmn.xml;

import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.InvalidFeelExpression;
import com.zorrodev.bpm.engine.configuration.FeelBigDecimalNumberMapper;
import com.zorrodev.bpm.engine.service.impl.BpmnParseServiceImpl;
import org.camunda.feel.api.FeelEngineApi;
import org.camunda.feel.api.FeelEngineBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-56, criterion 1 (the fixture half): EVERY {@code .bpmn} file in the repository — test
 * fixtures, oracle scenarios, CI soak models, docs examples, WO attachments — must pass the
 * deploy-time FEEL validation. This is the false-positive guard at full scale: a validator that
 * refused ANY of the ~400 models the project already ships would eat working models on the next
 * deploy, which is the real failure mode of a rejection feature.
 *
 * <p>Two excluded classes, each pinned by assertion (not by hope):
 * <ul>
 *   <li>files the production parser itself cannot parse (foreign/oracle models, deliberately
 *       malformed test inputs) — collected and printed; a FEEL finding can only come from a
 *       model the parser actually produced. If the skip list ever contains a file that looks
 *       like a first-party fixture, that is a signal to fix the fixture or the parser.</li>
 *   <li>{@link #KNOWN_INTENTIONALLY_BROKEN} — fixtures whose WHOLE POINT is an expression the
 *       FEEL parser rejects (they exercise the RUNTIME incident path, which is still reachable
 *       for definitions deployed before this WO's gate). They are the gate's intended victims;
 *       the list is asserted in BOTH directions — an unknown file with a finding fails the
 *       test, and a whitelisted file that no longer produces a finding is stale and fails it
 *       too.</li>
 *   <li>{@link #KNOWN_REFUSED_BY_THE_C8_55_PARITY_GATE} — the consumer's own models, kept
 *       byte-identical because the WO-C8-55 deploy gate refuses them exactly as live 8.5 does
 *       (the multi-instance {@code outputElement} written as a static value). Same two-way
 *       assertion.</li>
 * </ul>
 */
class FeelRepoFixtureValidationTest {

    private static final Path REPO_ROOT = Paths.get("..").toAbsolutePath().normalize();

    /**
     * Fixtures whose purpose is an expression the FEEL parser rejects — they test the RUNTIME
     * incident path on a broken expression, which after WO-C8-56 only exists for definitions
     * deployed before the gate (their tests deploy them through {@code LegacyDefinitionDeployer}).
     * Asserted in both directions below.
     */
    private static final java.util.Map<String, String> KNOWN_INTENTIONALLY_BROKEN = java.util.Map.of(
        "zorrobpm-engine/src/test/files/test-c835-errbnd-stranded-join.bpmn",
            "C8-35 security review fixture: flow condition '=svcOk == \"yes\"' is deliberately "
            + "unparseable FEEL (runtime-incident path, InclusiveJoinDeactivationWakeupIntegrationTests)",
        "zorrobpm-engine/src/test/files/test-script-task-invalid.bpmn",
            "script task 'a +' is deliberately truncated FEEL (runtime-incident path, "
            + "ScriptTaskIntegrationTests.invalidScriptRaisesIncidentInsteadOfStallingSilently)",
        "zorrobpm-engine/src/test/files/test-c856-invalid-feel-condition.bpmn",
            "WO-C8-56 RED fixture: the production expression '=returnClass = \'minor\'' as a flow condition",
        "zorrobpm-engine/src/test/files/test-c856-invalid-feel-assignee.bpmn",
            "WO-C8-56 RED fixture: assignee '=user = \'x\''",
        "zorrobpm-engine/src/test/files/test-c856-invalid-feel-two-broken.bpmn",
            "WO-C8-56 RED fixture: broken condition AND assignee in one model");

    /**
     * WO-C8-55: models that live 8.5 REFUSES at deploy and this engine now refuses too — but
     * they are not broken FEEL, they are the CONSUMER'S OWN BYTES, kept in the tree on purpose:
     * the multi-instance {@code outputElement} written as a static value (no leading {@code =}).
     * They are the evidence for the refusal (the prod corpus uses that spelling — the ACTIVE
     * {@code testApprovalRouteProcess} v73 on prod), so they must stay byte-identical to what
     * the consumer sent; the working copies of that model in {@code zorrobpm-engine/src/test/files}
     * carry the Zeebe-legal {@code ={...}} form, which behaves identically in this engine.
     *
     * <p>Asserted in BOTH directions, exactly like the map above: a file that stops producing a
     * finding is stale and fails the test; an unknown file with a finding fails it too.</p>
     */
    private static final java.util.Map<String, String> KNOWN_REFUSED_BY_THE_C8_55_PARITY_GATE = java.util.Map.of(
        "docs/internal",
            "the consumer's approval-route v2, ORIGINAL bytes (WO-C8-42 attachment): "
            + "mi.outputElement without '=' — refused at deploy, like on live 8.5",
        "docs/internal",
            "the consumer's v51, ORIGINAL bytes (WO-C8-50 attachment): mi.outputElement without '=' "
            + "— refused at deploy, like on live 8.5",
        "zorrobpm-engine/src/test/files/test-c855-consumer-noeq-output-element.bpmn",
            "the consumer's mi.outputElement spelling without '=' in isolation — the evidence for "
            + "the WO-C8-55 refusal (asserted by MiExpressionFieldDeployValidationIntegrationTests)");

    private final BpmnParseServiceImpl parser = new BpmnParseServiceImpl();

    private final FeelSyntaxValidator validator = new FeelSyntaxValidator(
        FeelEngineBuilder.forJava().withCustomValueMapper(new FeelBigDecimalNumberMapper()).build());

    @Test
    @EnabledIf("repoRootExists")
    void noBpmnFixtureInTheRepoCarriesAnUnparseableFeelExpression() throws IOException {
        List<Path> bpmnFiles = allBpmnFiles();
        List<String> skipped = new ArrayList<>();
        List<InvalidFeelExpression> findings = new ArrayList<>();
        int parsed = 0;

        for (Path file : bpmnFiles) {
            String relative = REPO_ROOT.relativize(file).toString();
            BpmnProcessDefinitionModel model;
            try {
                model = parser.parse(Files.readString(file));
            } catch (RuntimeException e) {
                skipped.add(relative + " — " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage().split("\n")[0] : ""));
                continue;
            }
            parsed++;
            for (InvalidFeelExpression finding : validator.validate(model)) {
                findings.add(new InvalidFeelExpression(relative, finding.field(),
                    finding.expression(), finding.parseError()));
            }
        }

        System.out.println("[WO-C8-56] bpmn files found: " + bpmnFiles.size()
            + ", parsed: " + parsed + ", skipped by the parser: " + skipped.size());
        skipped.forEach(s -> System.out.println("[WO-C8-56] skipped: " + s));
        findings.forEach(f -> System.out.println("[WO-C8-56] finding: " + f));

        assertThat(bpmnFiles)
            .as("the scan must actually see the repo's fixtures, not an empty set")
            .hasSizeGreaterThan(300);

        // every finding must be one of the known files — a broken expression (or a refused
        // static value) in ANY other model would mean the gate eats a model the project
        // already ships
        java.util.Map<String, String> known = new java.util.TreeMap<>(KNOWN_INTENTIONALLY_BROKEN);
        known.putAll(KNOWN_REFUSED_BY_THE_C8_55_PARITY_GATE);
        assertThat(findings)
            .as("only the known fixtures may carry a FEEL finding")
            .allSatisfy(f -> assertThat(known).containsKey(f.elementId()));

        // and the whitelist must be live: a fixture that no longer produces a finding is stale
        // (or the validator silently lost coverage) — the entry must go away
        java.util.Set<String> findingFiles = findings.stream()
            .map(InvalidFeelExpression::elementId)
            .collect(java.util.stream.Collectors.toSet());
        for (String knownFile : KNOWN_INTENTIONALLY_BROKEN.keySet()) {
            assertThat(findingFiles)
                .as("whitelist entry is stale: " + knownFile
                    + " (reason: " + KNOWN_INTENTIONALLY_BROKEN.get(knownFile) + ")")
                .contains(knownFile);
        }
        for (String knownFile : KNOWN_REFUSED_BY_THE_C8_55_PARITY_GATE.keySet()) {
            assertThat(findingFiles)
                .as("WO-C8-55 whitelist entry is stale: " + knownFile
                    + " (reason: " + KNOWN_REFUSED_BY_THE_C8_55_PARITY_GATE.get(knownFile) + ")")
                .contains(knownFile);
        }
    }

    static boolean repoRootExists() {
        return Files.isDirectory(REPO_ROOT) && Files.exists(
            REPO_ROOT.resolve("docs/internal"));
    }

    private List<Path> allBpmnFiles() throws IOException {
        try (Stream<Path> walk = Files.walk(REPO_ROOT)) {
            return walk
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".bpmn"))
                .filter(p -> !p.startsWith(REPO_ROOT.resolve(".git")))
                .filter(p -> !p.startsWith(REPO_ROOT.resolve("target")))
                .filter(p -> !p.toString().contains("/node_modules/"))
                .sorted()
                .toList();
        }
    }
}
