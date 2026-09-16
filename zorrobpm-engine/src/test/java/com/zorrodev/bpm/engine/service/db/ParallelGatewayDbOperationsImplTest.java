package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.engine.entity.ParallelGatewayEntity;
import com.zorrodev.bpm.engine.entity.TokenEntity;
import com.zorrodev.bpm.engine.repository.ParallelGatewayRepository;
import com.zorrodev.bpm.engine.repository.TokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ParallelGatewayDbOperationsImplTest {

    @Mock private ParallelGatewayRepository parallelGatewayRepository;
    @Mock private TokenRepository tokenRepository;
    @InjectMocks private ParallelGatewayDbOperationsImpl db;

    @Test
    void recordParallelGatewayArrival_savesWhenNotExists() {
        UUID pi = UUID.randomUUID();
        when(parallelGatewayRepository.existsByProcessInstanceIdAndGatewayElementIdAndEnteredFlowId(pi, "g", "f")).thenReturn(false);
        db.recordParallelGatewayArrival(pi, "g", "f");
        verify(parallelGatewayRepository).save(any(ParallelGatewayEntity.class));
    }

    @Test
    void recordParallelGatewayArrival_skipsWhenExists() {
        UUID pi = UUID.randomUUID();
        when(parallelGatewayRepository.existsByProcessInstanceIdAndGatewayElementIdAndEnteredFlowId(pi, "g", "f")).thenReturn(true);
        db.recordParallelGatewayArrival(pi, "g", "f");
        verify(parallelGatewayRepository, never()).save(any(ParallelGatewayEntity.class));
    }

    @Test
    void getParallelGatewayArrivedFlows_returnsFlows() {
        UUID pi = UUID.randomUUID();
        when(parallelGatewayRepository.findEnteredFlows(pi, "g")).thenReturn(List.of("f1", "f2"));
        Set<String> result = db.getParallelGatewayArrivedFlows(pi, "g");
        assertThat(result).containsExactlyInAnyOrder("f1", "f2");
    }

    @Test
    void clearParallelGatewayArrivals_deletes() {
        UUID pi = UUID.randomUUID();
        db.clearParallelGatewayArrivals(pi, "g");
        verify(parallelGatewayRepository).deleteByProcessInstanceIdAndGatewayElementId(pi, "g");
    }

    @Test
    void recordInclusiveExpected_savesMarker() {
        UUID pi = UUID.randomUUID();
        db.recordInclusiveExpected(pi, "g", 3);
        ArgumentCaptor<ParallelGatewayEntity> captor = ArgumentCaptor.forClass(ParallelGatewayEntity.class);
        verify(parallelGatewayRepository).save(captor.capture());
        assertThat(captor.getValue().getExpectedCount()).isEqualTo(3);
        assertThat(captor.getValue().getEnteredFlowId()).isEqualTo("g");
    }

    @Test
    void getInclusiveExpected_returnsFirst() {
        UUID pi = UUID.randomUUID();
        when(parallelGatewayRepository.findExpectedCounts(pi, "g")).thenReturn(List.of(5));
        assertThat(db.getInclusiveExpected(pi, "g")).isEqualTo(5);
    }

    @Test
    void setPendingBranches_saves() {
        UUID tokenId = UUID.randomUUID();
        TokenEntity e = new TokenEntity(); e.setId(tokenId); e.setPendingBranches(0);
        when(tokenRepository.findById(tokenId)).thenReturn(Optional.of(e));
        db.setPendingBranches(tokenId, 3);
        assertThat(e.getPendingBranches()).isEqualTo(3);
        verify(tokenRepository).save(e);
    }

    @Test
    void decrementPendingBranches_decrements() {
        UUID tokenId = UUID.randomUUID();
        TokenEntity e = new TokenEntity(); e.setId(tokenId); e.setPendingBranches(3);
        // WO-REL-40 (B-5): row-locked read, not plain findById
        when(tokenRepository.findByIdForUpdate(tokenId)).thenReturn(Optional.of(e));
        assertThat(db.decrementPendingBranches(tokenId)).isEqualTo(2);
        verify(tokenRepository).save(e);
    }

    @Test
    void decrementPendingBranches_returnsMinus1WhenNull() {
        UUID tokenId = UUID.randomUUID();
        TokenEntity e = new TokenEntity(); e.setId(tokenId); e.setPendingBranches(null);
        // WO-REL-40 (B-5): row-locked read, not plain findById
        when(tokenRepository.findByIdForUpdate(tokenId)).thenReturn(Optional.of(e));
        assertThat(db.decrementPendingBranches(tokenId)).isEqualTo(-1);
        verify(tokenRepository, never()).save(any(TokenEntity.class));
    }
}
