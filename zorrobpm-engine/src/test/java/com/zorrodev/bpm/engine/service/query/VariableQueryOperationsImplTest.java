package com.zorrodev.bpm.engine.service.query;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.query.VariableQuery;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.mapper.VariableMapper;
import com.zorrodev.bpm.engine.repository.VariableRepository;
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
class VariableQueryOperationsImplTest {

    @Mock private VariableRepository variableRepository;
    @Mock private VariableMapper variableMapper;
    @Mock private QueryPaginationSupport queryPaginationSupport;
    @InjectMocks private VariableQueryOperationsImpl impl;

    @Test
    void findVariables_withAllowedPdIds_appliesFilterAndDelegatesToRepository() {
        VariableQuery query = new VariableQuery();
        query.setPageIndex(0);
        query.setPageSize(10);
        UUID allowedPdId = UUID.randomUUID();

        ProcessVariableEntity entity = new ProcessVariableEntity();
        entity.setId(UUID.randomUUID());
        entity.setName("k-allowed");
        entity.setType(ProcessVariableType.STRING);
        entity.setTextValue("v");
        Page<ProcessVariableEntity> page = new PageImpl<>(List.of(entity), PageRequest.of(0, 10), 1);
        when(variableRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);
        ProcessVariable dto = new ProcessVariable();
        dto.setName("k-allowed");
        dto.setType(ProcessVariableType.STRING);
        dto.setValue("v");
        // Variables uses Sort.unsorted()
        PageRequest clamped = PageRequest.of(0, 10, Sort.unsorted());
        when(queryPaginationSupport.clampedPage(0, 10, Sort.unsorted())).thenReturn(clamped);
        PagedDataDTO<ProcessVariable> dtoResult = new PagedDataDTO<>();
        dtoResult.setData(List.of(dto));
        dtoResult.setTotalElements(1L);
        when(queryPaginationSupport.processInstanceInAllowedDefinitions(List.of(allowedPdId))).thenReturn((root, q, cb) -> cb.conjunction());
        doReturn(dtoResult).when(queryPaginationSupport).toDTO(eq(page), any());

        PagedDataDTO<ProcessVariable> result = impl.findVariables(query, List.of(allowedPdId));

        ArgumentCaptor<Specification<ProcessVariableEntity>> specCaptor = ArgumentCaptor.forClass(Specification.class);
        verify(variableRepository).findAll(specCaptor.capture(), eq(clamped));
        Specification<ProcessVariableEntity> captured = specCaptor.getValue();
        assertThat(captured).isNotNull();

        verify(queryPaginationSupport).processInstanceInAllowedDefinitions(List.of(allowedPdId));
        verify(queryPaginationSupport).clampedPage(0, 10, Sort.unsorted());
        verify(variableRepository).findAll(any(Specification.class), eq(clamped));
        assertThat(result.getData()).containsExactly(dto);
    }

    @Test
    void findVariables_emptyAllowed_returnsEmptyPageAndDoesNotCallRepository() {
        VariableQuery query = new VariableQuery();
        query.setPageIndex(0);
        query.setPageSize(10);
        PagedDataDTO<ProcessVariable> emptyDto = new PagedDataDTO<>();
        emptyDto.setData(List.of());
        emptyDto.setTotalElements(0L);
        emptyDto.setPageIndex(0);
        emptyDto.setPageSize(10);
        doReturn(emptyDto).when(queryPaginationSupport).emptyPage(query);

        PagedDataDTO<ProcessVariable> result = impl.findVariables(query, List.of());

        assertThat(result.getData()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
        verify(queryPaginationSupport).emptyPage(query);
        verify(variableRepository, never()).findAll(any(Specification.class), any(PageRequest.class));
        verify(queryPaginationSupport, never()).clampedPage(any(), any(), any());
        verify(queryPaginationSupport, never()).processInstanceInAllowedDefinitions(any());
    }

    @Test
    void findVariables_emptyAllowed_mutationWouldCallRepository() {
        // Named mutation (P-67): if the `allowedPdIds.isEmpty()` guard is removed,
        // findVariables(List.of()) must NOT reach the repository — it must return emptyPage.
        VariableQuery query = new VariableQuery();
        query.setPageIndex(0);
        query.setPageSize(10);
        PagedDataDTO<ProcessVariable> emptyDto = new PagedDataDTO<>();
        emptyDto.setData(List.of());
        emptyDto.setTotalElements(0L);
        doReturn(emptyDto).when(queryPaginationSupport).emptyPage(query);

        impl.findVariables(query, List.of());

        verify(queryPaginationSupport).emptyPage(query);
        verify(variableRepository, never()).findAll(any(Specification.class), any(PageRequest.class));
    }

    @Test
    void findVariables_nullAllowed_doesNotUseEmptyPage() {
        VariableQuery query = new VariableQuery();
        query.setPageIndex(0);
        query.setPageSize(10);
        ProcessVariableEntity entity = new ProcessVariableEntity();
        entity.setId(UUID.randomUUID());
        Page<ProcessVariableEntity> page = new PageImpl<>(List.of(entity), PageRequest.of(0, 10), 1);
        when(variableRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);
        PageRequest clamped = PageRequest.of(0, 10, Sort.unsorted());
        when(queryPaginationSupport.clampedPage(any(), any(), any())).thenReturn(clamped);
        PagedDataDTO<ProcessVariable> dtoResult = new PagedDataDTO<>();
        dtoResult.setData(List.of(new ProcessVariable()));
        doReturn(dtoResult).when(queryPaginationSupport).toDTO(any(), any());

        PagedDataDTO<ProcessVariable> result = impl.findVariables(query, null);

        verify(queryPaginationSupport, never()).emptyPage(any());
        verify(variableRepository).findAll(any(Specification.class), eq(clamped));
        verify(queryPaginationSupport, never()).processInstanceInAllowedDefinitions(any());
        assertThat(result).isNotNull();
    }

    @Test
    void findVariables_unsorted_used() {
        VariableQuery query = new VariableQuery();
        query.setPageIndex(2);
        query.setPageSize(15);
        ProcessVariableEntity entity = new ProcessVariableEntity();
        entity.setId(UUID.randomUUID());
        Page<ProcessVariableEntity> page = new PageImpl<>(List.of(entity), PageRequest.of(2, 15), 1);
        when(variableRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);
        PageRequest clamped = PageRequest.of(2, 15, Sort.unsorted());
        when(queryPaginationSupport.clampedPage(2, 15, Sort.unsorted())).thenReturn(clamped);
        PagedDataDTO<ProcessVariable> expected = new PagedDataDTO<>();
        expected.setData(List.of(new ProcessVariable()));
        doReturn(expected).when(queryPaginationSupport).toDTO(eq(page), any());

        PagedDataDTO<ProcessVariable> result = impl.findVariables(query, null);

        verify(queryPaginationSupport).clampedPage(2, 15, Sort.unsorted());
        verify(queryPaginationSupport).toDTO(eq(page), any());
        assertThat(result).isNotNull();
        // Verify that Sort.unsorted() was used, not "createdAt" or "dueAt"
        verify(queryPaginationSupport).clampedPage(any(), any(), eq(Sort.unsorted()));
    }
}
