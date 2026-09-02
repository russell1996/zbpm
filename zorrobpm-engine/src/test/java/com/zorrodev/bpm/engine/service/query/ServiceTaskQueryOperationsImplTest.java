package com.zorrodev.bpm.engine.service.query;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.query.ServiceTaskQuery;
import com.zorrodev.bpm.contract.model.ServiceTask;
import com.zorrodev.bpm.engine.entity.ServiceTaskEntity;
import com.zorrodev.bpm.engine.mapper.ServiceTaskMapper;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
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

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ServiceTaskQueryOperationsImplTest {

    @Mock private ServiceTaskRepository serviceTaskRepository;
    @Mock private ServiceTaskMapper serviceTaskMapper;
    @Mock private QueryPaginationSupport queryPaginationSupport;
    @InjectMocks private ServiceTaskQueryOperationsImpl impl;

    @Test
    void findServiceTasks_withAllowedPdIds_appliesFilterAndDelegatesToRepository() {
        ServiceTaskQuery query = new ServiceTaskQuery();
        query.setPageIndex(0);
        query.setPageSize(10);
        UUID allowedPdId = UUID.randomUUID();

        ServiceTaskEntity entity = new ServiceTaskEntity();
        entity.setId(UUID.randomUUID());
        Page<ServiceTaskEntity> page = new PageImpl<>(List.of(entity), PageRequest.of(0, 10), 1);
        when(serviceTaskRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);
        ServiceTask dto = new ServiceTask();
        dto.setId(entity.getId());
        PageRequest clamped = PageRequest.of(0, 10, Sort.by("createdAt").descending());
        when(queryPaginationSupport.clampedPage(0, 10, Sort.by("createdAt").descending())).thenReturn(clamped);
        PagedDataDTO<ServiceTask> bulkResult = new PagedDataDTO<>();
        bulkResult.setData(List.of(dto));
        bulkResult.setTotalElements(1L);
        doReturn(bulkResult).when(queryPaginationSupport).toDTOBulk(eq(page), any());

        PagedDataDTO<ServiceTask> result = impl.findServiceTasks(query, List.of(allowedPdId));

        // verify filter was applied via Specification that checks processDefinitionId in allowedPdIds
        ArgumentCaptor<Specification<ServiceTaskEntity>> specCaptor = ArgumentCaptor.forClass(Specification.class);
        verify(serviceTaskRepository).findAll(specCaptor.capture(), eq(clamped));
        Specification<ServiceTaskEntity> captured = specCaptor.getValue();
        assertThat(captured).isNotNull();

        // prove the spec contains allowedPdId filter by invoking it with mocked Criteria API
        Root<ServiceTaskEntity> root = mock(Root.class);
        CriteriaQuery<?> cq = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Path<Object> processDefinitionIdPath = mock(Path.class);
        Predicate inPredicate = mock(Predicate.class);
        when(root.get("processDefinitionId")).thenReturn((Path) processDefinitionIdPath);
        when(processDefinitionIdPath.in(anyCollection())).thenReturn(inPredicate);

        captured.toPredicate(root, cq, cb);
        verify(processDefinitionIdPath).in(List.of(allowedPdId));

        verify(queryPaginationSupport).clampedPage(0, 10, Sort.by("createdAt").descending());
        verify(queryPaginationSupport).toDTOBulk(eq(page), any());
        assertThat(result.getData()).containsExactly(dto);
    }

    @Test
    void findServiceTasks_emptyAllowed_returnsEmptyPageAndDoesNotCallRepository() {
        ServiceTaskQuery query = new ServiceTaskQuery();
        query.setPageIndex(0);
        query.setPageSize(10);
        PagedDataDTO<ServiceTask> emptyDto = new PagedDataDTO<>();
        emptyDto.setData(List.of());
        emptyDto.setTotalElements(0L);
        emptyDto.setPageIndex(0);
        emptyDto.setPageSize(10);
        doReturn(emptyDto).when(queryPaginationSupport).emptyPage(query);

        PagedDataDTO<ServiceTask> result = impl.findServiceTasks(query, List.of());

        assertThat(result.getData()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
        verify(queryPaginationSupport).emptyPage(query);
        verify(serviceTaskRepository, never()).findAll(any(Specification.class), any(PageRequest.class));
        verify(queryPaginationSupport, never()).clampedPage(any(), any(), any());
        verify(queryPaginationSupport, never()).toDTOBulk(any(), any());
    }

    @Test
    void findServiceTasks_emptyAllowed_mutationWouldCallRepository() {
        // Named mutation (P-67): if the `allowedPdIds.isEmpty()` guard is removed,
        // findServiceTasks(List.of()) must NOT reach the repository — it must return emptyPage.
        // This test fails when the guard is removed because repository would be called.
        ServiceTaskQuery query = new ServiceTaskQuery();
        query.setPageIndex(0);
        query.setPageSize(10);
        PagedDataDTO<ServiceTask> emptyDto = new PagedDataDTO<>();
        emptyDto.setData(List.of());
        emptyDto.setTotalElements(0L);
        doReturn(emptyDto).when(queryPaginationSupport).emptyPage(query);

        impl.findServiceTasks(query, List.of());

        verify(queryPaginationSupport).emptyPage(query);
        verify(serviceTaskRepository, never()).findAll(any(Specification.class), any(PageRequest.class));
    }

    @Test
    void findServiceTasks_nullAllowed_doesNotUseEmptyPage() {
        ServiceTaskQuery query = new ServiceTaskQuery();
        query.setPageIndex(0);
        query.setPageSize(10);
        ServiceTaskEntity entity = new ServiceTaskEntity();
        entity.setId(UUID.randomUUID());
        Page<ServiceTaskEntity> page = new PageImpl<>(List.of(entity), PageRequest.of(0, 10), 1);
        when(serviceTaskRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);
        PageRequest clamped = PageRequest.of(0, 10, Sort.by("createdAt").descending());
        when(queryPaginationSupport.clampedPage(any(), any(), any())).thenReturn(clamped);
        PagedDataDTO<ServiceTask> bulk = new PagedDataDTO<>();
        bulk.setData(List.of(new ServiceTask()));
        doReturn(bulk).when(queryPaginationSupport).toDTOBulk(any(), any());

        PagedDataDTO<ServiceTask> result = impl.findServiceTasks(query, null);

        verify(queryPaginationSupport, never()).emptyPage(any());
        verify(serviceTaskRepository).findAll(any(Specification.class), eq(clamped));
        assertThat(result).isNotNull();
    }

    @Test
    void getServiceTask_returnsMapped() {
        UUID id = UUID.randomUUID();
        ServiceTaskEntity entity = new ServiceTaskEntity();
        entity.setId(id);
        ServiceTask dto = new ServiceTask();
        dto.setId(id);
        when(serviceTaskRepository.findById(id)).thenReturn(Optional.of(entity));
        when(serviceTaskMapper.toDTO(entity)).thenReturn(dto);

        ServiceTask result = impl.getServiceTask(id);

        assertThat(result).isEqualTo(dto);
        verify(serviceTaskRepository).findById(id);
        verify(serviceTaskMapper).toDTO(entity);
    }

    @Test
    void getServiceTask_throwsWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(serviceTaskRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> impl.getServiceTask(id))
            .isInstanceOf(java.util.NoSuchElementException.class);

        verify(serviceTaskRepository).findById(id);
        verify(serviceTaskMapper, never()).toDTO(any());
    }
}
