package com.zorrodev.bpm.engine.dmn;

import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.service.DmnService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-SCALE-1: concurrent DMN deployment test on real PostgreSQL.
 * Calls the REAL DmnService.deploy() from two parallel threads.
 * Both DMNs share the same decision id ("scale1RaceDecision") but have different
 * content — both enter version creation, and the advisory lock
 * ({@code AdvisoryDeployLock.acquireForKey("dmn:" + decisionId)}) must serialize
 * them to produce sequential versions (1 and 2), not duplicate version 1.
 *
 * Mirror of {@code ProcessDefinitionPgIT.concurrentDeploy_sameKey_differentVersions_noViolation},
 * one-to-one in structure.
 *
 * POF (G-N): commenting out acquireForKey in DmnServiceImpl.deploy → RED (unique
 * violation on decision_id+version); restoring → GREEN. H2 proves nothing here:
 * the H2 branch of the lock is a silent no-op.
 */
@Tag("pg")
public class DmnDeployConcurrencyPgIT extends PostgresIT {

    @Autowired DmnService dmnService;
    @Autowired JdbcTemplate jdbc;

    private static final String DECISION_ID = "scale1RaceDecision";

    // Same decision id, different content (different output value).
    private static final String DMN_V1 = """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" xmlns:dmndi="https://www.omg.org/spec/DMN/20191111/DMNDI/" id="Definitions_scale1Race" name="scale1Race" namespace="http://camunda.org/schema/1.0/dmn" exporter="Camunda Modeler" exporterVersion="5.0.0">
          <decision id="scale1RaceDecision" name="Scale1 Race">
            <decisionTable id="DecisionTable_R1" hitPolicy="FIRST">
              <input id="Input_R1" label="Category">
                <inputExpression id="InputExpression_R1" typeRef="string" expressionLanguage="feel">
                  <text>category</text>
                </inputExpression>
              </input>
              <output id="Output_R1" label="Discount" name="discount" typeRef="number" />
              <rule id="Rule_R1">
                <inputEntry id="In_R1"><text>"gold"</text></inputEntry>
                <outputEntry id="Out_R1"><text>20</text></outputEntry>
              </rule>
            </decisionTable>
          </decision>
        </definitions>
        """;

    private static final String DMN_V2 = """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" xmlns:dmndi="https://www.omg.org/spec/DMN/20191111/DMNDI/" id="Definitions_scale1Race" name="scale1Race" namespace="http://camunda.org/schema/1.0/dmn" exporter="Camunda Modeler" exporterVersion="5.0.0">
          <decision id="scale1RaceDecision" name="Scale1 Race">
            <decisionTable id="DecisionTable_R1" hitPolicy="FIRST">
              <input id="Input_R1" label="Category">
                <inputExpression id="InputExpression_R1" typeRef="string" expressionLanguage="feel">
                  <text>category</text>
                </inputExpression>
              </input>
              <output id="Output_R1" label="Discount" name="discount" typeRef="number" />
              <rule id="Rule_R1">
                <inputEntry id="In_R1"><text>"gold"</text></inputEntry>
                <outputEntry id="Out_R1"><text>30</text></outputEntry>
              </rule>
            </decisionTable>
          </decision>
        </definitions>
        """;

    @BeforeEach
    void setUp() {
        // Clean up existing versions
        jdbc.update("DELETE FROM dmn_definitions WHERE decision_id = ?", DECISION_ID);
    }

    @Test
    void concurrentDeploy_sameDecisionId_differentVersions_noViolation() throws Exception {
        CountDownLatch readyGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        // Thread 1: deploy DMN_V1 via REAL service
        Future<?> f1 = pool.submit(() -> {
            try {
                readyGate.await();
                dmnService.deploy(DMN_V1);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Thread 2: deploy DMN_V2 (same decision id, different content) via REAL service
        Future<?> f2 = pool.submit(() -> {
            try {
                readyGate.await();
                dmnService.deploy(DMN_V2);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        readyGate.countDown();

        f1.get();
        f2.get();
        pool.shutdown();

        // Verify: two versions exist, sequential (1 and 2)
        var versions = jdbc.queryForList(
            "SELECT version FROM dmn_definitions WHERE decision_id = ? ORDER BY version", DECISION_ID);
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).get("version")).isEqualTo(1);
        assertThat(versions.get(1).get("version")).isEqualTo(2);

        // Cleanup
        jdbc.update("DELETE FROM dmn_definitions WHERE decision_id = ?", DECISION_ID);
    }
}
