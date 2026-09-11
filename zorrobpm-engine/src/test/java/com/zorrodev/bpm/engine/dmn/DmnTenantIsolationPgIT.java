package com.zorrodev.bpm.engine.dmn;

import com.zorrodev.bpm.contract.model.DmnDecision;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.service.DmnService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-SEC-40: DMN cross-tenant isolation on real PostgreSQL.
 * Proves that (a) the 20260804-066 migration adds process_definition_id to dmn_definitions,
 * (b) deploy(xml, pdId) persists the scope, (c) listDecisions(allowedPdIds) filters by scope,
 * (d) findProcessDefinitionId resolves the owning process definition.
 *
 * G-N POF: comment the visibleMeta() filter in DmnServiceImpl.listDecisions(allowedPdIds) ->
 * scopedList_allowedOnlyOwnPds shows the foreign decision (RED); with the filter it does not (GREEN).
 */
@Tag("pg")
public class DmnTenantIsolationPgIT extends PostgresIT {

    private static final String DMN_ID_A = "discountPgA";
    private static final String DMN_ID_B = "discountPgB";

    private static final String DMN_A = """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" xmlns:dmndi="https://www.omg.org/spec/DMN/20191111/DMNDI/" id="Definitions_discountPgA" name="discountPgA" namespace="http://camunda.org/schema/1.0/dmn" exporter="Camunda Modeler" exporterVersion="5.0.0">
          <decision id="discountPgA" name="Discount PG A">
            <decisionTable id="DecisionTable_A1" hitPolicy="FIRST">
              <input id="Input_A1" label="Category">
                <inputExpression id="InputExpression_A1" typeRef="string" expressionLanguage="feel">
                  <text>category</text>
                </inputExpression>
              </input>
              <output id="Output_A1" label="Discount" name="discount" typeRef="number" />
              <rule id="Rule_A1">
                <inputEntry id="In_A1"><text>"gold"</text></inputEntry>
                <outputEntry id="Out_A1"><text>20</text></outputEntry>
              </rule>
            </decisionTable>
          </decision>
        </definitions>
        """;

    private static final String DMN_B = """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" xmlns:dmndi="https://www.omg.org/spec/DMN/20191111/DMNDI/" id="Definitions_discountPgB" name="discountPgB" namespace="http://camunda.org/schema/1.0/dmn" exporter="Camunda Modeler" exporterVersion="5.0.0">
          <decision id="discountPgB" name="Discount PG B">
            <decisionTable id="DecisionTable_B1" hitPolicy="FIRST">
              <input id="Input_B1" label="Category">
                <inputExpression id="InputExpression_B1" typeRef="string" expressionLanguage="feel">
                  <text>category</text>
                </inputExpression>
              </input>
              <output id="Output_B1" label="Discount" name="discount" typeRef="number" />
              <rule id="Rule_B1">
                <inputEntry id="In_B1"><text>"gold"</text></inputEntry>
                <outputEntry id="Out_B1"><text>30</text></outputEntry>
              </rule>
            </decisionTable>
          </decision>
        </definitions>
        """;

    @Autowired DmnService dmnService;
    @Autowired JdbcTemplate jdbc;

    private UUID pdIdA;
    private UUID pdIdB;

    @BeforeEach
    void setUp() {
        pdIdA = UUID.randomUUID();
        pdIdB = UUID.randomUUID();
        jdbc.update("DELETE FROM dmn_definitions WHERE decision_id IN (?, ?)", DMN_ID_A, DMN_ID_B);
        // Scoped deploys go through the real prod path (DmnService.deploy(xml, pdId))
        dmnService.deploy(DMN_A, pdIdA);
        dmnService.deploy(DMN_B, pdIdB);
    }

    @Test
    void migration_addsProcessDefinitionIdColumn() {
        // If the 20260804-066 migration did not run, the column lookup below fails on PG
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.columns WHERE table_name='dmn_definitions' AND column_name='process_definition_id'",
            Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void deploy_scopesDecisionToProcessDefinition() {
        // Round-trip through the real repository: scope persisted by prod code
        Optional<UUID> scopeA = dmnService.findProcessDefinitionId(DMN_ID_A);
        Optional<UUID> scopeB = dmnService.findProcessDefinitionId(DMN_ID_B);
        assertThat(scopeA).contains(pdIdA);
        assertThat(scopeB).contains(pdIdB);
    }

    @Test
    void scopedList_allowedOnlyOwnPds() {
        List<DmnDecision> result = dmnService.listDecisions(Set.of(pdIdA));
        assertThat(result).extracting(DmnDecision::getId)
            .contains(DMN_ID_A)
            .doesNotContain(DMN_ID_B);
    }

    @Test
    void scopedList_emptyAllowed_seesNothing() {
        List<DmnDecision> result = dmnService.listDecisions(Set.of());
        assertThat(result).extracting(DmnDecision::getId)
            .doesNotContain(DMN_ID_A, DMN_ID_B);
    }

    @Test
    void scopedList_nullAllowed_seesAll() {
        List<DmnDecision> result = dmnService.listDecisions(null);
        assertThat(result).extracting(DmnDecision::getId)
            .contains(DMN_ID_A, DMN_ID_B);
    }

    @Test
    void scopedList_bothAllowed_seesBoth() {
        List<DmnDecision> result = dmnService.listDecisions(Set.of(pdIdA, pdIdB));
        assertThat(result).extracting(DmnDecision::getId)
            .contains(DMN_ID_A, DMN_ID_B);
    }
}
