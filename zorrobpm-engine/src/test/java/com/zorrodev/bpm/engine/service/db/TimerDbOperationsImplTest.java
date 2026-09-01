package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.engine.dto.TimerJob;
import com.zorrodev.bpm.engine.dto.TimerStartJob;
import com.zorrodev.bpm.engine.entity.TimerJobEntity;
import com.zorrodev.bpm.engine.entity.TimerStartJobEntity;
import com.zorrodev.bpm.engine.repository.TimerJobRepository;
import com.zorrodev.bpm.engine.repository.TimerStartJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TimerDbOperationsImplTest {

    @Mock private TimerJobRepository timerJobRepository;
    @Mock private TimerStartJobRepository timerStartJobRepository;
    @Mock private JdbcTemplate jdbcTemplate;
    @InjectMocks private TimerDbOperationsImpl db;

    @Test
    void createTimerJob_saves() {
        UUID act = UUID.randomUUID();
        UUID id = db.createTimerJob(act, Instant.now().plusSeconds(60), null, null, null, null);
        assertThat(id).isNotNull();
        verify(timerJobRepository).save(any(TimerJobEntity.class));
    }

    @Test
    void createEventSubprocessTimerJob_saves() {
        UUID pi = UUID.randomUUID();
        UUID id = db.createEventSubprocessTimerJob(pi, Instant.now().plusSeconds(60), "esp");
        assertThat(id).isNotNull();
        ArgumentCaptor<TimerJobEntity> captor = ArgumentCaptor.forClass(TimerJobEntity.class);
        verify(timerJobRepository).save(captor.capture());
        assertThat(captor.getValue().getEventSubprocessId()).isEqualTo("esp");
        assertThat(captor.getValue().getActivityId()).isNull();
    }

    @Test
    void createTimerStartJob_4arg_saves() {
        UUID pd = UUID.randomUUID();
        db.createTimerStartJob("key", pd, "el", Instant.now().plusSeconds(60));
        verify(timerStartJobRepository).save(any(TimerStartJobEntity.class));
    }

    @Test
    void createTimerStartJob_5arg_savesWithCount() {
        UUID pd = UUID.randomUUID();
        db.createTimerStartJob("key", pd, "el", Instant.now().plusSeconds(60), 5);
        ArgumentCaptor<TimerStartJobEntity> captor = ArgumentCaptor.forClass(TimerStartJobEntity.class);
        verify(timerStartJobRepository).save(captor.capture());
        assertThat(captor.getValue().getRemainingCount()).isEqualTo(5);
    }

    @Test
    void deleteTimerJobsByProcessInstanceId_deletes() {
        UUID pi = UUID.randomUUID();
        db.deleteTimerJobsByProcessInstanceId(pi);
        verify(timerJobRepository).deleteByProcessInstanceId(pi);
    }

    @Test
    void deleteTimerStartJobsByKey_deletes() {
        db.deleteTimerStartJobsByKey("key");
        verify(timerStartJobRepository).deleteByProcessKey("key");
    }

    @Test
    void findDueTimerJobs_returnsMapped() {
        TimerJobEntity e = new TimerJobEntity(); e.setId(UUID.randomUUID()); e.setActivityId(UUID.randomUUID());
        when(timerJobRepository.findByFiredFalseAndDueAtLessThanEqual(any(Instant.class))).thenReturn(List.of(e));
        List<TimerJob> result = db.findDueTimerJobs(Instant.now());
        assertThat(result).hasSize(1);
    }

    @Test
    void findDueTimerJobsLocked_returnsMapped() {
        TimerJobEntity e = new TimerJobEntity(); e.setId(UUID.randomUUID());
        when(timerJobRepository.findDueLocked(any(Instant.class), eq(10))).thenReturn(List.of(e));
        List<TimerJob> result = db.findDueTimerJobsLocked(Instant.now(), 10);
        assertThat(result).hasSize(1);
    }

    @Test
    void findDueTimerStartJobs_returnsMapped() {
        TimerStartJobEntity e = new TimerStartJobEntity(); e.setId(UUID.randomUUID());
        when(timerStartJobRepository.findByFiredFalseAndDueAtLessThanEqual(any(Instant.class))).thenReturn(List.of(e));
        List<TimerStartJob> result = db.findDueTimerStartJobs(Instant.now());
        assertThat(result).hasSize(1);
    }

    @Test
    void findDueTimerStartJobsLocked_returnsMapped() {
        TimerStartJobEntity e = new TimerStartJobEntity(); e.setId(UUID.randomUUID());
        when(timerStartJobRepository.findDueLocked(any(Instant.class), eq(10))).thenReturn(List.of(e));
        List<TimerStartJob> result = db.findDueTimerStartJobsLocked(Instant.now(), 10);
        assertThat(result).hasSize(1);
    }

    @Test
    void claimTimerJob_returnsTrueWhenUnlocked() {
        UUID id = UUID.randomUUID();
        when(jdbcTemplate.queryForList(eq("SELECT id FROM timer_jobs WHERE id = ? AND fired = false FOR UPDATE SKIP LOCKED"), eq(UUID.class), eq(id))).thenReturn(List.of(id));
        when(timerJobRepository.claimTimerJob(id)).thenReturn(1);
        assertThat(db.claimTimerJob(id)).isTrue();
    }

    @Test
    void claimTimerJob_returnsFalseWhenLocked() {
        UUID id = UUID.randomUUID();
        when(jdbcTemplate.queryForList(any(String.class), eq(UUID.class), eq(id))).thenReturn(List.of());
        assertThat(db.claimTimerJob(id)).isFalse();
    }

    @Test
    void claimTimerStartJob_returnsTrueWhenUnlocked() {
        UUID id = UUID.randomUUID();
        when(jdbcTemplate.queryForList(eq("SELECT id FROM timer_start_jobs WHERE id = ? AND fired = false FOR UPDATE SKIP LOCKED"), eq(UUID.class), eq(id))).thenReturn(List.of(id));
        when(timerStartJobRepository.claimTimerStartJob(id)).thenReturn(1);
        assertThat(db.claimTimerStartJob(id)).isTrue();
    }

    @Test
    void recordTimerJobError_callsRepo() {
        UUID id = UUID.randomUUID();
        db.recordTimerJobError(id, "err");
        verify(timerJobRepository).recordTimerJobError(id, "err");
    }

    @Test
    void recordTimerStartJobError_callsRepo() {
        UUID id = UUID.randomUUID();
        db.recordTimerStartJobError(id, "err");
        verify(timerStartJobRepository).recordTimerStartJobError(id, "err");
    }
}
