package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.service.DmnService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-AUDIT-3 (P1, criterion 1): {@code listDecisions} serves full DTOs on real
 * PostgreSQL through the TEXT-less projection path — the returned DTO carries
 * parsed structure but never the raw {@code dmn} XML blob (there is no XML field
 * on it by construction), and the latest version wins.
 */
@Tag("pg")
class DmnListProjectionPgIT extends PostgresIT {

    @Autowired
    private DmnService dmnService;

    @Test
    void listDecisions_returnsLatestFullDtoWithoutXml() throws Exception {
        String id = "pgProjProbe" + UUID.randomUUID().toString().replace("-", "");
        String base = Files.readString(Path.of("src/test/files/test-discount.dmn"));
        String xml = base.replace("discount", id);
        dmnService.deploy(xml);
        dmnService.deploy(xml);

        var probe = dmnService.listDecisions(null).stream()
            .filter(d -> id.equals(d.getId()))
            .toList();
        assertThat(probe).as("one row per decisionId (latest wins)").hasSize(1);
        assertThat(probe.get(0).getVersion()).as("latest version served").isEqualTo(2);
        assertThat(probe.get(0).getRules()).as("parsed structure present, XML never leaves the DB").isNotEmpty();
    }
}
