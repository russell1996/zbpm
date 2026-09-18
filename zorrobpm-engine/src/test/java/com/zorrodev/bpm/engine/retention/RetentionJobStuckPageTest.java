package com.zorrodev.bpm.engine.retention;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-REL-33 F37 (GREEN): страница, ни одна строка которой не удаляется, не
 * гоняется по кругу вечно — и курсор движется мимо плохих строк, а не бросает
 * очередь навсегда.
 *
 * <p>Полная страница (2 = batchSize), обе строки бьются: первый проход кладёт
 * обе в skip-set и выходит по {@code pageDeleted==0} (миллисекунды, не вечный
 * цикл). Второй сценарий: одна строка бьётся, одна удаляется — плохой id
 * исключается из следующего опроса (3-arg overload с растущим skip-set),
 * хорошая удаляется, проход завершается.
 */
@ExtendWith(MockitoExtension.class)
class RetentionJobStuckPageTest {

    @Mock private RetentionBatchProcessor batchProcessor;
    private RetentionConfig config;
    private RetentionJob job;

    @BeforeEach
    void setUp() {
        config = new RetentionConfig();
        job = new RetentionJob(config, batchProcessor);
    }

    private void stubIdleProcessors(int batchSize) {
        when(batchProcessor.findEligibleInstances(any(), anyInt(), eq(batchSize))).thenReturn(List.of());
        when(batchProcessor.deleteOrphanedBoundaryTimers(any(), eq(batchSize))).thenReturn(0);
    }

    @Test
    void fullPageOfFailingSubmissions_exitsImmediately() {
        config.setTtlDays(90);
        config.setBatchSize(2);
        stubIdleProcessors(2);
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        when(batchProcessor.findEligibleSubmissions(any(), eq(2), any()))
            .thenReturn(List.of(id1, id2));
        when(batchProcessor.deleteSubmission(id1)).thenThrow(new RuntimeException("row locked"));
        when(batchProcessor.deleteSubmission(id2)).thenThrow(new RuntimeException("row locked"));

        // Было бы вечным циклом — теперь выход за миллисекунды, каждая строка ровно 1 раз.
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> job.run());

        verify(batchProcessor, times(1)).deleteSubmission(id1);
        verify(batchProcessor, times(1)).deleteSubmission(id2);
        verify(batchProcessor, times(1)).findEligibleSubmissions(any(), eq(2), any());
    }

    @Test
    void badRowSkipped_goodRowDeleted_nextPollExcludesBadRow() {
        config.setTtlDays(90);
        config.setBatchSize(2);
        stubIdleProcessors(2);
        UUID bad = UUID.randomUUID();
        UUID good = UUID.randomUUID();

        when(batchProcessor.findEligibleSubmissions(any(), eq(2), any()))
            .thenReturn(List.of(bad, good), List.of());
        when(batchProcessor.deleteSubmission(bad)).thenThrow(new RuntimeException("row locked"));
        when(batchProcessor.deleteSubmission(good)).thenReturn(1);

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> job.run());

        // Порядок: опрос → удаление обеих → следующий опрос уже с bad в skip-set.
        InOrder order = inOrder(batchProcessor);
        order.verify(batchProcessor).findEligibleSubmissions(any(), eq(2), eq(java.util.Set.of()));
        order.verify(batchProcessor).deleteSubmission(bad);
        order.verify(batchProcessor).deleteSubmission(good);
        order.verify(batchProcessor).findEligibleSubmissions(any(), eq(2), eq(java.util.Set.of(bad)));
    }

    @Test
    void findEligibleSubmissions_skipSetExcludesIds_inSql() {
        // 3-arg overload обязан нести NOT IN (:skipIds) — курсор реально движется в SQL,
        // а не фильтруется в памяти после выборки той же страницы.
        RetentionBatchProcessor real = new RetentionBatchProcessor(
            org.mockito.Mockito.mock(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate.class));
        org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc =
            (org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate)
                readField(real, "jdbc");
        UUID bad = UUID.randomUUID();
        when(jdbc.queryForList(any(String.class),
            any(org.springframework.jdbc.core.namedparam.SqlParameterSource.class),
            eq(UUID.class))).thenReturn(List.of());

        real.findEligibleSubmissions(java.time.Instant.now(), 2, java.util.Set.of(bad));

        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(),
            any(org.springframework.jdbc.core.namedparam.SqlParameterSource.class),
            eq(UUID.class));
        org.assertj.core.api.Assertions.assertThat(sql.getValue()).contains("NOT IN (:skipIds)");
    }

    private static Object readField(Object target, String name) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
