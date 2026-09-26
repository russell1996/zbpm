package com.zorrodev.bpm.engine.service.query;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.query.TimerJobQuery;
import com.zorrodev.bpm.contract.model.TimerJob;
import com.zorrodev.bpm.engine.entity.TimerJobEntity;
import com.zorrodev.bpm.engine.mapper.TimerJobMapper;
import com.zorrodev.bpm.engine.repository.TimerJobRepository;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TimerJobQueryOperationsImplTest {

    @Mock private TimerJobRepository timerJobRepository;
    @Mock private TimerJobMapper timerJobMapper;
    @Mock private QueryPaginationSupport queryPaginationSupport;
    @InjectMocks private TimerJobQueryOperationsImpl impl;

    @Test
    void findTimerJobs_withAllowedPdIds_appliesFilterAndDelegatesToRepository() {
        TimerJobQuery query = new TimerJobQuery();
        query.setPageIndex(0);
        query.setPageSize(10);
        UUID allowedPdId = UUID.randomUUID();
        query.setId(UUID.randomUUID());

        TimerJobEntity entity = new TimerJobEntity();
        entity.setId(UUID.randomUUID());
        Page<TimerJobEntity> page = new PageImpl<>(List.of(entity), PageRequest.of(0, 10), 1);
        when(timerJobRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);
        TimerJob dto = new TimerJob();
        dto.setId(entity.getId());
        PageRequest clamped = PageRequest.of(0, 10, Sort.by("dueAt").ascending());
        when(queryPaginationSupport.clampedPage(0, 10, Sort.by("dueAt").ascending())).thenReturn(clamped);
        PagedDataDTO<TimerJob> dtoResult = new PagedDataDTO<>();
        dtoResult.setData(List.of(dto));
        dtoResult.setTotalElements(1L);
        when(queryPaginationSupport.processInstanceInAllowedDefinitions(List.of(allowedPdId))).thenReturn((root, q, cb) -> cb.conjunction());
        doReturn(dtoResult).when(queryPaginationSupport).toDTO(eq(page), any());

        PagedDataDTO<TimerJob> result = impl.findTimerJobs(query, List.of(allowedPdId));

        ArgumentCaptor<Specification<TimerJobEntity>> specCaptor = ArgumentCaptor.forClass(Specification.class);
        verify(timerJobRepository).findAll(specCaptor.capture(), eq(clamped));
        Specification<TimerJobEntity> captured = specCaptor.getValue();
        assertThat(captured).isNotNull();

        // verify processInstanceInAllowedDefinitions was called via queryPaginationSupport
        verify(queryPaginationSupport).processInstanceInAllowedDefinitions(List.of(allowedPdId));
        verify(queryPaginationSupport).clampedPage(0, 10, Sort.by("dueAt").ascending());
        verify(timerJobRepository).findAll(any(Specification.class), eq(clamped));
        assertThat(result.getData()).containsExactly(dto);
    }

    @Test
    void findTimerJobs_emptyAllowed_returnsEmptyPageAndDoesNotCallRepository() {
        TimerJobQuery query = new TimerJobQuery();
        query.setPageIndex(0);
        query.setPageSize(10);
        PagedDataDTO<TimerJob> emptyDto = new PagedDataDTO<>();
        emptyDto.setData(List.of());
        emptyDto.setTotalElements(0L);
        emptyDto.setPageIndex(0);
        emptyDto.setPageSize(10);
        doReturn(emptyDto).when(queryPaginationSupport).emptyPage(query);

        PagedDataDTO<TimerJob> result = impl.findTimerJobs(query, List.of());

        assertThat(result.getData()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
        verify(queryPaginationSupport).emptyPage(query);
        verify(timerJobRepository, never()).findAll(any(Specification.class), any(PageRequest.class));
        verify(queryPaginationSupport, never()).clampedPage(any(), any(), any());
        verify(queryPaginationSupport, never()).processInstanceInAllowedDefinitions(any());
    }

    @Test
    void findTimerJobs_emptyAllowed_mutationWouldCallRepository() {
        // Named mutation (P-67): if the `allowedPdIds.isEmpty()` guard is removed,
        // findTimerJobs(List.of()) must NOT reach the repository — it must return emptyPage.
        TimerJobQuery query = new TimerJobQuery();
        query.setPageIndex(0);
        query.setPageSize(10);
        PagedDataDTO<TimerJob> emptyDto = new PagedDataDTO<>();
        emptyDto.setData(List.of());
        emptyDto.setTotalElements(0L);
        doReturn(emptyDto).when(queryPaginationSupport).emptyPage(query);

        impl.findTimerJobs(query, List.of());

        verify(queryPaginationSupport).emptyPage(query);
        verify(timerJobRepository, never()).findAll(any(Specification.class), any(PageRequest.class));
    }

    @Test
    void findTimerJobs_nullAllowed_doesNotUseEmptyPage() {
        TimerJobQuery query = new TimerJobQuery();
        query.setPageIndex(0);
        query.setPageSize(10);
        TimerJobEntity entity = new TimerJobEntity();
        entity.setId(UUID.randomUUID());
        Page<TimerJobEntity> page = new PageImpl<>(List.of(entity), PageRequest.of(0, 10), 1);
        when(timerJobRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);
        PageRequest clamped = PageRequest.of(0, 10, Sort.by("dueAt").ascending());
        when(queryPaginationSupport.clampedPage(any(), any(), any())).thenReturn(clamped);
        PagedDataDTO<TimerJob> dtoResult = new PagedDataDTO<>();
        dtoResult.setData(List.of(new TimerJob()));
        doReturn(dtoResult).when(queryPaginationSupport).toDTO(any(), any());

        PagedDataDTO<TimerJob> result = impl.findTimerJobs(query, null);

        verify(queryPaginationSupport, never()).emptyPage(any());
        verify(timerJobRepository).findAll(any(Specification.class), eq(clamped));
        verify(queryPaginationSupport, never()).processInstanceInAllowedDefinitions(any());
        assertThat(result).isNotNull();
    }

    @Test
    void findTimerJobs_toDTO_and_dueAtSorting_used() {
        TimerJobQuery query = new TimerJobQuery();
        query.setPageIndex(2);
        query.setPageSize(15);
        TimerJobEntity entity = new TimerJobEntity();
        entity.setId(UUID.randomUUID());
        Page<TimerJobEntity> page = new PageImpl<>(List.of(entity), PageRequest.of(2, 15), 1);
        when(timerJobRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);
        TimerJob dto = new TimerJob();
        dto.setId(entity.getId());
        PageRequest clamped = PageRequest.of(2, 15, Sort.by("dueAt").ascending());
        when(queryPaginationSupport.clampedPage(2, 15, Sort.by("dueAt").ascending())).thenReturn(clamped);
        PagedDataDTO<TimerJob> expected = new PagedDataDTO<>();
        expected.setData(List.of(dto));
        doReturn(expected).when(queryPaginationSupport).toDTO(eq(page), any());

        PagedDataDTO<TimerJob> result = impl.findTimerJobs(query, null);

        verify(queryPaginationSupport).clampedPage(2, 15, Sort.by("dueAt").ascending());
        verify(queryPaginationSupport).toDTO(eq(page), any());
        assertThat(result.getData()).containsExactly(dto);
    }
}
