package com.zorrodev.bpm.engine.retention;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-REL-54 criterion 1: число SQL-statement'ов растёт на ПАЧКУ, не на
 * инстанс. Старый путь (claimAndDeleteOneInstance): DISTINCT + eligible-SELECT
 * на КАЖДЫЙ инстанс. Новый (claimAndDeleteBatch): один eligible-SELECT на
 * пачку, DISTINCT — раз за прогон (у job'а, сюда приходит параметром).
 *
 * <p>POF-мутация: вернуть DISTINCT внутрь selectEligiblePerDefinition —
 * этот тест красный (лишний queryForList на каждую пачку).
 */
@ExtendWith(MockitoExtension.class)
class Rel54StatementCountTest {

    @Mock private NamedParameterJdbcTemplate jdbc;

    @Test
    @SuppressWarnings("unchecked")
    void batchIssuesOneEligibleSelectPerBatch_noDistinctInside() {
        RetentionBatchProcessor proc = new RetentionBatchProcessor(jdbc);
        UUID id = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class), eq(UUID.class)))
            .thenReturn(List.of(id), List.of());
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);

        // Две пачки: первая с инстансом, вторая пустая (конец).
        RetentionBatchProcessor.ClaimedBatch first =
            proc.claimAndDeleteBatch(Instant.now(), 90, 25, List.of(30));
        assertThat(first.instanceIds()).containsExactly(id);
        RetentionBatchProcessor.ClaimedBatch second =
            proc.claimAndDeleteBatch(Instant.now(), 90, 25, List.of(30));
        assertThat(second.instanceIds()).isEmpty();

        // Eligible-SELECT — ровно по одному на пачку (2 вызова queryForList-UUID
        // на eligible + 1 token-SELECT первой пачки = 3 всего), DISTINCT-внутри —
        // ноль: queryForList(Integer) не вызывался вообще.
        verify(jdbc, times(3)).queryForList(anyString(),
            any(SqlParameterSource.class), eq(UUID.class));
        // DISTINCT(Integer) — ноль: список приходит параметром от job'а.
        verify(jdbc, times(0)).queryForList(anyString(),
            any(SqlParameterSource.class), eq(Integer.class));
    }

    @Test
    void loadDistinctTtls_singleDistinctQuery() {
        RetentionBatchProcessor proc = new RetentionBatchProcessor(jdbc);
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class), eq(Integer.class)))
            .thenReturn(List.of(30));
        assertThat(proc.loadDistinctTtls()).containsExactly(30);
        verify(jdbc, times(1)).queryForList(anyString(),
            any(MapSqlParameterSource.class), eq(Integer.class));
    }
}
