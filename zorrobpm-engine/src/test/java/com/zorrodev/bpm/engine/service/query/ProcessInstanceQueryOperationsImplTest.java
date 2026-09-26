package com.zorrodev.bpm.engine.service.query;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.query.ProcessInstanceQuery;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.mapper.ProcessInstanceMapper;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.service.DBService;
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
class ProcessInstanceQueryOperationsImplTest {

    @Mock private ProcessInstanceRepository processInstanceRepository;
    @Mock private ProcessInstanceMapper processInstanceMapper;
    @Mock private QueryPaginationSupport queryPaginationSupport;
    @Mock private DBService dbService;
    @InjectMocks private ProcessInstanceQueryOperationsImpl impl;

    @Test
    void getProcessInstance_delegatesToDbService() {
        UUID id = UUID.randomUUID();
        ProcessInstance expected = new ProcessInstance();
        expected.setId(id);
        when(dbService.getProcessInstance(id)).thenReturn(expected);

        ProcessInstance result = impl.getProcessInstance(id);

        assertThat(result).isEqualTo(expected);
        verify(dbService).getProcessInstance(id);
        verify(processInstanceRepository, never()).findAll(any(Specification.class), any(PageRequest.class));
    }

    @Test
    void findProcessInstances_withAllowedPdIds_appliesFilterAndDelegatesToRepository() {
        ProcessInstanceQuery query = new ProcessInstanceQuery();
        query.setPageIndex(0);
        query.setPageSize(10);
        UUID allowedPdId = UUID.randomUUID();

        ProcessInstanceEntity entity = new ProcessInstanceEntity();
        entity.setId(UUID.randomUUID());
        Page<ProcessInstanceEntity> page = new PageImpl<>(List.of(entity), PageRequest.of(0, 10), 1);
        when(processInstanceRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);
        ProcessInstance dto = new ProcessInstance();
        dto.setId(entity.getId());
        PageRequest clamped = PageRequest.of(0, 10, Sort.by("startedAt").descending());
        when(queryPaginationSupport.clampedPage(0, 10, Sort.by("startedAt").descending())).thenReturn(clamped);
        PagedDataDTO<ProcessInstance> bulkResult = new PagedDataDTO<>();
        bulkResult.setData(List.of(dto));
        bulkResult.setTotalElements(1L);
        doReturn(bulkResult).when(queryPaginationSupport).toDTOBulk(eq(page), any());

        PagedDataDTO<ProcessInstance> result = impl.findProcessInstances(query, List.of(allowedPdId));

        ArgumentCaptor<Specification<ProcessInstanceEntity>> specCaptor = ArgumentCaptor.forClass(Specification.class);
        verify(processInstanceRepository).findAll(specCaptor.capture(), eq(clamped));
        Specification<ProcessInstanceEntity> captured = specCaptor.getValue();
        assertThat(captured).isNotNull();

        Root<ProcessInstanceEntity> root = mock(Root.class);
        CriteriaQuery<?> cq = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Path<Object> processDefinitionIdPath = mock(Path.class);
        Predicate inPredicate = mock(Predicate.class);
        when(root.get("processDefinitionId")).thenReturn((Path) processDefinitionIdPath);
        when(processDefinitionIdPath.in(anyCollection())).thenReturn(inPredicate);

        captured.toPredicate(root, cq, cb);
        verify(processDefinitionIdPath).in(List.of(allowedPdId));

        verify(queryPaginationSupport).clampedPage(0, 10, Sort.by("startedAt").descending());
        verify(queryPaginationSupport).toDTOBulk(eq(page), any());
        assertThat(result.getData()).containsExactly(dto);
    }

    @Test
    void findProcessInstances_emptyAllowed_returnsEmptyPageAndDoesNotCallRepository() {
        ProcessInstanceQuery query = new ProcessInstanceQuery();
        query.setPageIndex(0);
        query.setPageSize(10);
        PagedDataDTO<ProcessInstance> emptyDto = new PagedDataDTO<>();
        emptyDto.setData(List.of());
        emptyDto.setTotalElements(0L);
        emptyDto.setPageIndex(0);
        emptyDto.setPageSize(10);
        doReturn(emptyDto).when(queryPaginationSupport).emptyPage(query);

        PagedDataDTO<ProcessInstance> result = impl.findProcessInstances(query, List.of());

        assertThat(result.getData()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
        verify(queryPaginationSupport).emptyPage(query);
        verify(processInstanceRepository, never()).findAll(any(Specification.class), any(PageRequest.class));
        verify(queryPaginationSupport, never()).clampedPage(any(), any(), any());
        verify(queryPaginationSupport, never()).toDTOBulk(any(), any());
        verify(dbService, never()).getProcessInstance(any());
    }

    @Test
    void findProcessInstances_emptyAllowed_mutationWouldCallRepository() {
        // Named mutation (P-67): if the `allowedPdIds.isEmpty()` guard is removed,
        // findProcessInstances(List.of()) must NOT reach the repository — it must return emptyPage.
        ProcessInstanceQuery query = new ProcessInstanceQuery();
        query.setPageIndex(0);
        query.setPageSize(10);
        PagedDataDTO<ProcessInstance> emptyDto = new PagedDataDTO<>();
        emptyDto.setData(List.of());
        emptyDto.setTotalElements(0L);
        doReturn(emptyDto).when(queryPaginationSupport).emptyPage(query);

        impl.findProcessInstances(query, List.of());

        verify(queryPaginationSupport).emptyPage(query);
        verify(processInstanceRepository, never()).findAll(any(Specification.class), any(PageRequest.class));
    }

    @Test
    void findProcessInstances_nullAllowed_doesNotUseEmptyPage() {
        ProcessInstanceQuery query = new ProcessInstanceQuery();
        query.setPageIndex(0);
        query.setPageSize(10);
        ProcessInstanceEntity entity = new ProcessInstanceEntity();
        entity.setId(UUID.randomUUID());
        Page<ProcessInstanceEntity> page = new PageImpl<>(List.of(entity), PageRequest.of(0, 10), 1);
        when(processInstanceRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);
        PageRequest clamped = PageRequest.of(0, 10, Sort.by("startedAt").descending());
        when(queryPaginationSupport.clampedPage(any(), any(), any())).thenReturn(clamped);
        PagedDataDTO<ProcessInstance> bulk = new PagedDataDTO<>();
        bulk.setData(List.of(new ProcessInstance()));
        doReturn(bulk).when(queryPaginationSupport).toDTOBulk(any(), any());

        PagedDataDTO<ProcessInstance> result = impl.findProcessInstances(query, null);

        verify(queryPaginationSupport, never()).emptyPage(any());
        verify(processInstanceRepository).findAll(any(Specification.class), eq(clamped));
        assertThat(result).isNotNull();
    }

    @Test
    void findProcessInstances_startedAtSorting_used() {
        ProcessInstanceQuery query = new ProcessInstanceQuery();
        query.setPageIndex(1);
        query.setPageSize(20);
        // Verify that clampedPage is called with Sort.by("startedAt").descending(), not "createdAt"
        Page<ProcessInstanceEntity> page = new PageImpl<>(List.of(), PageRequest.of(1, 20), 0);
        when(processInstanceRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);
        PageRequest clamped = PageRequest.of(1, 20, Sort.by("startedAt").descending());
        when(queryPaginationSupport.clampedPage(1, 20, Sort.by("startedAt").descending())).thenReturn(clamped);
        PagedDataDTO<ProcessInstance> bulk = new PagedDataDTO<>();
        bulk.setData(List.of());
        doReturn(bulk).when(queryPaginationSupport).toDTOBulk(eq(page), any());

        impl.findProcessInstances(query, null);

        verify(queryPaginationSupport).clampedPage(1, 20, Sort.by("startedAt").descending());
        verify(processInstanceRepository).findAll(any(Specification.class), eq(clamped));
    }
}
