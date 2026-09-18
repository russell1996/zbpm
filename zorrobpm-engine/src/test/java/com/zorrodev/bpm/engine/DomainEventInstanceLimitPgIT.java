package com.zorrodev.bpm.engine;

import com.zorrodev.bpm.engine.entity.DomainEventEntity;
import com.zorrodev.bpm.engine.repository.DomainEventRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-AUDIT-3 (P4): the per-instance events query is bounded — 5 seeded rows, limit 2
 * returns exactly 2 (oldest first). Without the {@code LIMIT} the same call returns 5.
 */
@Tag("pg")
class DomainEventInstanceLimitPgIT extends PostgresIT {

    @Autowired
    private DomainEventRepository domainEventRepository;

    @Test
    void findByProcessInstanceId_honorsLimit() {
        UUID pi = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            DomainEventEntity e = new DomainEventEntity();
            e.setId(UUID.randomUUID());
            e.setType("probe");
            e.setVersion(1);
            e.setOccurredAt(Instant.now().plusSeconds(i));
            e.setProcessDefinitionId(UUID.randomUUID());
            e.setProcessInstanceId(pi);
            domainEventRepository.save(e);
        }

        List<DomainEventEntity> window = domainEventRepository.findByProcessInstanceId(pi, 2);
        assertThat(window).as("bounded window, not the whole instance history").hasSize(2);
        assertThat(window.get(0).getOccurredAt())
            .as("oldest first")
            .isBeforeOrEqualTo(window.get(1).getOccurredAt());
    }
}
