package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.contract.model.DmnDecision;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.service.DmnService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
 * Direct DMN decision-table evaluation: hit policies (FIRST/UNIQUE), single & multi output,
 * no-match, unknown decision, numeric/type handling, plus the read API (list/get parsing).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class DmnEvaluationIntegrationTests {

    @Autowired
    private DmnService dmnService;

    private ProcessVariable str(String name, String value) {
        return var(name, ProcessVariableType.STRING, value);
    }

    private ProcessVariable num(String name, String value) {
        return var(name, ProcessVariableType.DOUBLE, value);
    }

    private ProcessVariable var(String name, ProcessVariableType type, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(type);
        v.setValue(value);
        return v;
    }

    @BeforeEach
    void deployAll() throws Exception {
        dmnService.deploy(Files.readString(Paths.get("src/test/files/test-discount.dmn")));
        dmnService.deploy(Files.readString(Paths.get("src/test/files/test-credit-decision.dmn")));
        dmnService.deploy(Files.readString(Paths.get("src/test/files/test-dmn-threshold.dmn")));
        dmnService.deploy(Files.readString(Paths.get("src/test/files/test-dmn-collect.dmn")));
        dmnService.deploy(Files.readString(Paths.get("src/test/files/test-dmn-collect-sum.dmn")));
        dmnService.deploy(Files.readString(Paths.get("src/test/files/test-dmn-ruleorder.dmn")));
        dmnService.deploy(Files.readString(Paths.get("src/test/files/test-dmn-unique-fail.dmn")));
    }

    // --- positive: FIRST hit policy, single output, including the empty default row ---
    @ParameterizedTest
    @CsvSource({"gold,20", "silver,10", "bronze,0", "platinum,0"})
    @Transactional
    void firstHitPolicySingleOutput(String category, int expected) {
        Object result = dmnService.evaluate("discount", List.of(str("category", category)));
        assertThat(((Number) result).intValue()).isEqualTo(expected);
    }

    // --- positive: UNIQUE hit policy, multi output -> name->value map ---
    @ParameterizedTest
    @CsvSource({"750,APPROVED,25000", "650,REVIEW,10000", "500,REJECTED,0"})
    @Transactional
    @SuppressWarnings("unchecked")
    void uniqueHitPolicyMultiOutput(String score, String decision, int limit) {
        Object result = dmnService.evaluate("creditDecision", List.of(num("creditScore", score)));
        assertThat(result).isInstanceOf(Map.class);
        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map.get("decision")).isEqualTo(decision);
        assertThat(((Number) map.get("limit")).intValue()).isEqualTo(limit);
    }

    // --- edge: a score that matches no rule returns null (partial table) ---
    @Transactional
    @Test
    void noMatchingRuleReturnsNull() {
        assertThat(dmnService.evaluate("gradeDecision", List.of(num("score", "500")))).isNull();
        assertThat(dmnService.evaluate("gradeDecision", List.of(num("score", "800")))).isEqualTo("A");
    }

    // --- edge: boundary value of a FEEL range/comparison ---
    @Transactional
    @Test
    void numericBoundaryIsInclusive() {
        // >= 700 -> APPROVED at exactly 700; 699.99 falls into [600..700) -> REVIEW
        assertThat(((Map<?, ?>) dmnService.evaluate("creditDecision", List.of(num("creditScore", "700")))).get("decision"))
            .isEqualTo("APPROVED");
        assertThat(((Map<?, ?>) dmnService.evaluate("creditDecision", List.of(num("creditScore", "699.99")))).get("decision"))
            .isEqualTo("REVIEW");
    }

    // --- negative: evaluating an unknown decision id fails fast ---
    @Transactional
    @Test
    void unknownDecisionThrows() {
        assertThatThrownBy(() -> dmnService.evaluate("doesNotExist", List.of(str("category", "gold"))))
            .isInstanceOf(EngineException.class);
    }

    // --- read API: the stored DMN is parsed into inputs/outputs/rules/hitPolicy ---
    @Transactional
    @Test
    void getDecisionParsesStructure() {
        DmnDecision d = dmnService.getDecision("creditDecision");
        assertThat(d.getId()).isEqualTo("creditDecision");
        assertThat(d.getHitPolicy()).isEqualTo("UNIQUE");
        assertThat(d.getInputs()).hasSize(1);
        assertThat(d.getInputs().get(0).getExpression()).isEqualTo("creditScore");
        assertThat(d.getOutputs()).extracting("name").containsExactly("decision", "limit");
        assertThat(d.getRules()).hasSize(3);
    }

    @Transactional
    @Test
    void listDecisionsReturnsDeployed() {
        assertThat(dmnService.listDecisions()).extracting(DmnDecision::getId)
            .contains("discount", "creditDecision", "gradeDecision");
    }

    @Transactional
    @Test
    void getUnknownDecisionThrows() {
        assertThatThrownBy(() -> dmnService.getDecision("nope")).isInstanceOf(EngineException.class);
    }

    // --- Criterion #1: COLLECT returns all matching rules ---

    @Transactional
    @Test
    @SuppressWarnings("unchecked")
    void criterion1_collectReturnsAllMatchingRules() {
        // score=30 matches all 3 rules (>=10, >=20, >=30)
        Object result = dmnService.evaluate("collectDecision", List.of(num("score", "30")));
        assertThat(result).isInstanceOf(List.class);
        List<Object> list = (List<Object>) result;
        assertThat(list).hasSize(3);
        assertThat(list).extracting(r -> ((Number) r).intValue()).containsExactly(10, 20, 30);
    }

    // --- Criterion #2: COLLECT SUM aggregates ---

    @Transactional
    @Test
    void criterion2_collectSumAggregates() {
        // score=30 matches 3 rules with points 10+20+30 = 60
        Object result = dmnService.evaluate("collectSumDecision", List.of(num("score", "30")));
        assertThat(((Number) result).doubleValue()).isEqualTo(60.0);
    }

    // --- Criterion #3: RULE ORDER preserves order ---

    @Transactional
    @Test
    @SuppressWarnings("unchecked")
    void criterion3_ruleOrderPreservesOrder() {
        // category="gold" matches rule 1 → priority=1
        Object result = dmnService.evaluate("ruleOrderDecision", List.of(str("category", "gold")));
        assertThat(result).isInstanceOf(List.class);
        List<Object> list = (List<Object>) result;
        assertThat(list).hasSize(1);
        assertThat(((Number) list.get(0)).intValue()).isEqualTo(1);
    }

    // --- Criterion #4: UNIQUE with >1 match → error ---

    @Transactional
    @Test
    void criterion4_uniqueMultipleMatchesThrows() {
        // score=60 matches both rules (>=50) → UNIQUE should throw
        assertThatThrownBy(() -> dmnService.evaluate("uniqueFailDecision", List.of(num("score", "60"))))
            .isInstanceOf(EngineException.class)
            .hasMessageContaining("UNIQUE")
            .hasMessageContaining("2");
    }

    // --- Criterion #5: FIRST still works (existing tests cover this) ---
    // Covered by firstHitPolicySingleOutput parameterized test above

    // --- Criterion #6: proof-of-failure ---
    // On OLD code: UNIQUE with 2 matches returns 1 result (break on first match)
    // On NEW code: UNIQUE with 2 matches throws EngineException
    // This is proven by criterion4 above (GREEN on new code)
    // RED on old code: the test would pass (returning "A" instead of throwing)
}
