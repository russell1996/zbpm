package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.engine.TestMain;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

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
 * value is a null) — the strict profile runs the whole list
 * ({@link FeelIoMappingConformanceIntegrationTests}). Both write their own observed
 * snapshot, so the matrix keeps one column per profile.</p>
 */
@SpringBootTest(classes = TestMain.class,
    properties = "zorrobpm.engine.io-mapping.strict-missing=false")
@ActiveProfiles("test")
public class FeelIoMappingNullSemanticsIntegrationTests extends FeelIoMappingConformanceSupport {

    /** The root-1 cases of WO-C8-54 (live 8.5 → a null value). */
    private static final Set<String> NULL_SEMANTICS_CASES = Set.of(
        "num-round", "str-null", "null-literal", "missing-bare", "present-null",
        "missing-default", "path-missing", "mixed-string-num", "mixed-lt",
        "path-index-zero", "path-oob");

    @Override
    protected Set<String> includedCaseIds() {
        return NULL_SEMANTICS_CASES;
    }

    @Override
    protected String observedFile() {
        return System.getProperty("feel.observed.file", "target/feel-iomapping-observed-lenient.json");
    }
}
