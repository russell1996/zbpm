package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.service.DmnService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WO-ENG-22 (N16): DMN hit policy ANY was executed as FIRST — the
 * {@code default} branch of the hit-policy switch returned the first match
 * without checking that all matches agree (Camunda 8.8 hit-policy docs:
 * ANY = all satisfied rules must generate the same output, otherwise the
 * hit policy is violated).
 *
 * <p>All tests go through the public {@link DmnService#evaluate} end to
 * end (DMN XML in, result/exception out) — no copy of the matching logic
 * (G-N). POF link: on the unfixed code the violation tests return the
 * first rule's output instead of throwing, and the unknown-policy test
 * silently behaves as FIRST.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
class DmnAnyHitPolicyIntegrationTests {

    @Autowired
    private DmnService dmnService;

    private ProcessVariable num(String name, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(ProcessVariableType.DOUBLE);
        v.setValue(value);
        return v;
    }

    @BeforeEach
    void deployAll() throws Exception {
        dmnService.deploy(Files.readString(Paths.get("src/test/files/test-dmn-any-agree.dmn")));
        dmnService.deploy(Files.readString(Paths.get("src/test/files/test-dmn-any-conflict.dmn")));
        dmnService.deploy(Files.readString(Paths.get("src/test/files/test-dmn-any-composite.dmn")));
        dmnService.deploy(Files.readString(Paths.get("src/test/files/test-dmn-unknown-hitpolicy.dmn")));
    }

    // --- Criterion 1: two matches with the SAME output under ANY → success ---

    @Transactional
    @Test
    void eng22_anyAgreeingRules_returnTheOutput() {
        assertThat(dmnService.evaluate("anyAgreeDecision", List.of(num("score", "60"))))
            .isEqualTo("A");
    }

    @Transactional
    @Test
    void eng22_anyNumericallyEqualOutputs_agreeByValueSemantics() {
        // 10 vs 10.0: the same DMN number in different literal spellings.
        // Honesty note (verified by live probes, not assumed): this project's
        // FEEL bridge normalizes both spellings to Long(10), so even a naive
        // Objects.equals comparison agrees here — this test pins the CONTRACT
        // (numeric agreement is not a violation), while the POF link for the
        // comparison itself is the "no comparison at all" mutation (= the old
        // code), proven RED by the violation tests. The BigDecimal branch of
        // the comparison is defense-in-depth for value sources that do not
        // normalize (e.g. BigDecimal("10") vs ("10.0") with different scale).
        Object result = dmnService.evaluate("anyAgreeNumericDecision", List.of(num("score", "60")));
        assertThat(((Number) result).intValue()).isEqualTo(10);
    }

    // --- Criterion 2: two matches with DIFFERENT outputs under ANY → explicit model error ---

    @Transactional
    @Test
    void eng22_anyConflictingRules_throwModelViolation() {
        assertThatThrownBy(() -> dmnService.evaluate("anyConflictDecision", List.of(num("score", "60"))))
            .isInstanceOf(EngineException.class)
            .hasMessageContaining("ANY");
    }

    // --- Criterion 3: composite outputs and nulls compare correctly ---

    @Transactional
    @Test
    @SuppressWarnings("unchecked")
    void eng22_anyCompositeAgreeingMaps_returnTheMap() {
        Object result = dmnService.evaluate("anyCompositeAgreeDecision", List.of(num("score", "60")));
        assertThat(result).isInstanceOf(Map.class);
        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map.get("decision")).isEqualTo("APPROVED");
        assertThat(((Number) map.get("limit")).intValue()).isEqualTo(25000);
    }

    @Transactional
    @Test
    void eng22_anyNullOutputs_agreeWithEachOther() {
        assertThat(dmnService.evaluate("anyCompositeNullAgreeDecision", List.of(num("score", "60"))))
            .isNull();
    }

    @Transactional
    @Test
    void eng22_anyNullVsValue_conflicts() {
        assertThatThrownBy(() -> dmnService.evaluate("anyCompositeConflictDecision", List.of(num("score", "60"))))
            .isInstanceOf(EngineException.class)
            .hasMessageContaining("ANY");
    }

    // --- WO task item 2: unknown hit policy is rejected explicitly, not FIRST ---

    @Transactional
    @Test
    void eng22_unknownHitPolicy_rejectedExplicitly() {
        assertThatThrownBy(() -> dmnService.evaluate("unknownHitPolicyDecision", List.of(num("score", "60"))))
            .isInstanceOf(EngineException.class)
            .hasMessageContaining("hit policy");
    }
}
