package com.zorrodev.bpm.engine.retention;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-REL-33 п.1: {@code findEligibleInstances} обязан выбирать строки через
 * {@code FOR UPDATE SKIP LOCKED} — иначе две реплики multi-instance
 * развёртывания видят одни и те же строки и выполняют дублирующую работу.
 *
 * <p>Компилируется и на pre-fix базе: RED там — в SQL нет {@code SKIP LOCKED}.
 */
@ExtendWith(MockitoExtension.class)
class RetentionSkipLockedTest {

    @Mock private NamedParameterJdbcTemplate jdbc;
    private RetentionBatchProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new RetentionBatchProcessor(jdbc);
    }

    @Test
    void findEligibleInstances_selectsWithSkipLocked() {
        when(jdbc.queryForList(any(String.class), any(SqlParameterSource.class), eq(UUID.class)))
            .thenReturn(List.of());

        processor.findEligibleInstances(Instant.now(), 100);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), any(SqlParameterSource.class), eq(UUID.class));
        assertThat(sql.getValue()).contains("SKIP LOCKED");
    }
}
