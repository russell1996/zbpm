package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.engine.TestMain;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;

/**
 * WO-C8-54 — the io-mapping null semantics under the PRODUCTION default profile
 * ({@code zorrobpm.engine.io-mapping.strict-missing=false}).
 *
 * <p>WO-ENG-29/30 introduced a strict/lenient duality: a missing variable in an
 * io-mapping raised an incident in strict mode and a silent empty string in lenient
 * mode. The live Zeebe 8.5 measurement (the F1 campaign, oracle
 * {@code docs/analysis/c8-oracle/feel-iomapping.js}) shows NEITHER: a missing
 * variable, an unknown function, an incomparable comparison and an out-of-range
 * index all evaluate to a NULL VALUE — the job is created, the variable is a JSON
 * null, and the process continues. WO-C8-54 therefore makes BOTH profiles follow
 * the live 8.5 semantics, and this class is the proof for the production default:
 * the same case list, the same live-8.5 expectations, the other profile.</p>
 *
 * <p>The cases run here are the root-1 cases of WO-C8-54 (the ones whose live-8.5
 * value is a null) and the root-3 cases of WO-C8-55 (the static-value rule) — the strict
 * profile runs the whole list
 * ({@link FeelIoMappingConformanceIntegrationTests}). Both write their own observed
 * snapshot, so the matrix keeps one column per profile.</p>
 */
@SpringBootTest(classes = TestMain.class,
    properties = "zorrobpm.engine.io-mapping.strict-missing=false")
@ActiveProfiles("test")
public class FeelIoMappingNullSemanticsIntegrationTests extends FeelIoMappingConformanceSupport {

    /**
     * The root-1 cases of WO-C8-54 (live 8.5 → a null value) plus the root-3 cases of
     * WO-C8-55 (a source without {@code =} → a static value; an output source without
     * {@code =} → refused at deploy). Both sets are the ones whose live-8.5 value is pinned
     * for the PRODUCTION default profile — the flag itself is obsolete since WO-C8-54 (both
     * profiles follow live 8.5), and this class is the guard that the profile which actually
     * runs in production does too.
     */
    private static final Set<String> NULL_SEMANTICS_CASES = Set.of(
        "num-round", "str-null", "null-literal", "missing-bare", "present-null",
        "missing-default", "path-missing", "mixed-string-num", "mixed-lt",
        "path-index-zero", "path-oob",
        // WO-C8-55 (root 3): the static-value rule must hold under the production default too
        "noeq-literal", "noeq-var", "noeq-number", "noeq-json", "noeq-spaces",
        "noeq-output-static", "noeq-output-static-var");

    @Override
    protected Set<String> includedCaseIds() {
        return NULL_SEMANTICS_CASES;
    }

    @Override
    protected String observedFile() {
        return System.getProperty("feel.observed.file", "target/feel-iomapping-observed-lenient.json");
    }

    @Transactional
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void measure(Map<String, Object> c) throws Exception {
        measureCase(c);
    }
}
