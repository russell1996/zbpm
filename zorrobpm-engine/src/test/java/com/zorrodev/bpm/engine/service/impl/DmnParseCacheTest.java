package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.service.DmnService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-AUDIT-3 (P1): DMN hot-path must not re-parse XML per call.
 * Deploy stamps the parse cache; repeated {@code evaluate} of the same decision
 * hits it. Deltas (not absolutes) — the Spring context (and its cache) is shared
 * with other DMN test classes in this JVM run.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
class DmnParseCacheTest {

    @Autowired
    private DmnService dmnService;

    @Autowired
    private DmnServiceImpl dmnServiceImpl;

    private ProcessVariable str(String name, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(ProcessVariableType.STRING);
        v.setValue(value);
        return v;
    }

    @Test
    void deployPrewarmsCache_evaluateHitsWithoutParsing() throws Exception {
        String xml = Files.readString(Paths.get("src/test/files/test-discount.dmn"));
        dmnService.deploy(xml);

        var cache = dmnServiceImpl.parsedModelCache();
        // NOTE: Cache.stats() is an immutable snapshot — re-fetch after each action.
        long hits0 = cache.stats().hitCount();
        long miss0 = cache.stats().missCount();
        Object r = dmnService.evaluate("discount", List.of(str("category", "gold")));
        assertThat(((Number) r).intValue()).isEqualTo(20);
        assertThat(cache.stats().hitCount() - hits0).as("evaluate right after deploy hits the cache").isEqualTo(1);
        assertThat(cache.stats().missCount() - miss0).as("no re-parse on evaluate").isZero();
    }

    @Test
    void repeatedEvaluate_parsesOnce() throws Exception {
        String xml = Files.readString(Paths.get("src/test/files/test-discount.dmn"));
        dmnService.deploy(xml);
        var cache = dmnServiceImpl.parsedModelCache();
        cache.invalidateAll();

        long hits0 = cache.stats().hitCount();
        long miss0 = cache.stats().missCount();
        Object r1 = dmnService.evaluate("discount", List.of(str("category", "gold")));
        Object r2 = dmnService.evaluate("discount", List.of(str("category", "gold")));
        assertThat(((Number) r2).intValue()).isEqualTo(((Number) r1).intValue());
        assertThat(cache.stats().missCount() - miss0).as("exactly one parse for two evaluates").isEqualTo(1);
        assertThat(cache.stats().hitCount() - hits0).as("second evaluate hits the cache").isEqualTo(1);
    }

    @Test
    void listDecisions_returnsLatestVersion() throws Exception {
        String base = Files.readString(Paths.get("src/test/files/test-discount.dmn"));
        String xml = base.replace("discount", "cacheProbe");
        dmnService.deploy(xml);
        dmnService.deploy(xml);

        var probe = dmnService.listDecisions(null).stream()
            .filter(d -> "cacheProbe".equals(d.getId()))
            .toList();
        assertThat(probe).as("one row per decisionId (latest wins)").hasSize(1);
        assertThat(probe.get(0).getVersion()).as("latest version served").isEqualTo(2);
        assertThat(probe.get(0).getName()).isNotBlank();
    }
}
