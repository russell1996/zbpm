package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.engine.TestMain;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * WO-OPS-30 F1 — characterization measurements of FEEL / ioMapping behaviour on the
 * REAL engine path ({@code RuntimeService} → handlers → {@code ElementSupport} →
 * outbox job payload), against the case list measured on the live Zeebe 8.5.0 oracle
 * by {@code docs/analysis/c8-oracle/feel-iomapping.js}.
 *
 * <p>This class intentionally ASSERTS ONLY that the measurement itself happened
 * (a case produced an outcome). Divergences from Zeebe are collected in
 * {@code target/feel-iomapping-observed.json} and listed as bug-WO candidates in the
 * F1 report — fixing them is out of scope (WO-OPS-30 F1). G-N: every observation
 * goes through the production path; no inline copy of the mapping logic.</p>
 *
 * <p>The cases that carry a live-8.5 {@code expect} block (added by WO-C8-53 and
 * WO-C8-54) ARE asserted — the expected values come from the live Zeebe 8.5 run,
 * never from our own implementation (G-Q).</p>
 *
 * <p>Strict-missing is enabled by the test profile
 * ({@code zorrobpm.engine.io-mapping.strict-missing=true}). The production default
 * (false) is measured by {@link FeelIoMappingNullSemanticsIntegrationTests} — after
 * WO-C8-54 both profiles follow the live 8.5 null semantics.</p>
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class FeelIoMappingConformanceIntegrationTests extends FeelIoMappingConformanceSupport {

    @Transactional
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void measure(Map<String, Object> c) throws Exception {
        measureCase(c);
    }
}
