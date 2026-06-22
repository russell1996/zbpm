package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.DmnDefinitionEntity;
import com.zorrodev.bpm.engine.repository.DmnDefinitionRepository;
import com.zorrodev.bpm.engine.service.DmnService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** P3: redeploying a DMN decision adds a new version (keeps history) and evaluation uses the latest. */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class DmnVersioningIntegrationTests {

    @Autowired
    private DmnService dmnService;

    @Autowired
    private DmnDefinitionRepository dmnDefinitionRepository;

    private ProcessVariable var(String name, String value) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setType(ProcessVariableType.STRING);
        v.setValue(value);
        return v;
    }

    @Transactional
    @Test
    void redeployAddsAVersionAndEvaluationUsesTheLatest() throws Exception {
        // v1: gold -> 20; v2 (same decision id): gold -> 50. After both deploys, evaluate uses v2.
        dmnService.deploy(Files.readString(Paths.get("src/test/files/test-discount.dmn")));
        Object v1 = dmnService.evaluate("discount", List.of(var("category", "gold")));
        assertThat(((Number) v1).intValue()).isEqualTo(20);

        dmnService.deploy(Files.readString(Paths.get("src/test/files/test-discount-v2.dmn")));
        Object v2 = dmnService.evaluate("discount", List.of(var("category", "gold")));
        assertThat(((Number) v2).intValue()).isEqualTo(50);

        // both versions are retained
        List<DmnDefinitionEntity> versions = dmnDefinitionRepository.findAll().stream()
            .filter(e -> e.getDecisionId().equals("discount"))
            .toList();
        assertThat(versions).extracting(DmnDefinitionEntity::getVersion).contains(1, 2);
    }
}
