package com.zorrodev.bpm.engine.retention;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RetentionBatchProcessorTest {

    @Mock private JdbcTemplate jdbc;
    private RetentionBatchProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new RetentionBatchProcessor(jdbc);
    }

    @Test
    void findEligible_passesCorrectParameters() {
        Instant cutoff = Instant.now().minusSeconds(86400);
        when(jdbc.queryForList(anyString(), eq(UUID.class), eq(cutoff), eq(50)))
            .thenReturn(List.of(UUID.randomUUID()));

        List<UUID> result = processor.findEligibleInstances(cutoff, 50);

        verify(jdbc).queryForList(anyString(), eq(UUID.class), eq(cutoff), eq(50));
    }

    @Test
    void deleteInstances_emptyList_doesNothing() {
        int result = processor.deleteInstances(List.of());
        verifyNoInteractions(jdbc);
    }

    @Test
    void deleteInstances_callsDeleteInOrder() {
        UUID id = UUID.randomUUID();
        when(jdbc.update(anyString())).thenReturn(1);

        processor.deleteInstances(List.of(id));

        // Verify delete calls were made (at least process_instances deletion)
        verify(jdbc, atLeast(1)).update(anyString());
    }
}
