package com.zorrodev.bpm.engine.service.query;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.mapper.UserTaskMapper;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserTaskQueryOperationsImplTest {

    @Mock private UserTaskRepository userTaskRepository;
    @Mock private UserTaskMapper userTaskMapper;
    @Mock private QueryPaginationSupport queryPaginationSupport;
    @InjectMocks private UserTaskQueryOperationsImpl impl;

    @Test
    void findUserTasks_withAllowedPdIds_appliesFilterAndDelegatesToRepository() {
        UserTaskQuery query = new UserTaskQuery();
        query.setPageIndex(0);
        query.setPageSize(10);
        UUID allowedPdId = UUID.randomUUID();

        UserTaskEntity entity = new UserTaskEntity();
        entity.setId(UUID.randomUUID());
        Page<UserTaskEntity> page = new PageImpl<>(List.of(entity), PageRequest.of(0, 10), 1);
        when(userTaskRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);
        UserTask dto = new UserTask();
        dto.setId(entity.getId());
        PageRequest clamped = PageRequest.of(0, 10, Sort.by("createdAt").descending());
        when(queryPaginationSupport.clampedPage(0, 10, Sort.by("createdAt").descending())).thenReturn(clamped);
        PagedDataDTO<UserTask> bulkResult = new PagedDataDTO<>();
        bulkResult.setData(List.of(dto));
        bulkResult.setTotalElements(1L);
        doReturn(bulkResult).when(queryPaginationSupport).toDTOBulk(eq(page), any());

        PagedDataDTO<UserTask> result = impl.findUserTasks(query, List.of(allowedPdId));

        ArgumentCaptor<Specification<UserTaskEntity>> specCaptor = ArgumentCaptor.forClass(Specification.class);
        verify(userTaskRepository).findAll(specCaptor.capture(), eq(clamped));
        Specification<UserTaskEntity> captured = specCaptor.getValue();
        assertThat(captured).isNotNull();

        Root<UserTaskEntity> root = mock(Root.class);
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
    void findUserTasks_emptyAllowed_returnsEmptyPageAndDoesNotCallRepository() {
        UserTaskQuery query = new UserTaskQuery();
        query.setPageIndex(0);
        query.setPageSize(10);
        PagedDataDTO<UserTask> emptyDto = new PagedDataDTO<>();
        emptyDto.setData(List.of());
        emptyDto.setTotalElements(0L);
        emptyDto.setPageIndex(0);
        emptyDto.setPageSize(10);
        doReturn(emptyDto).when(queryPaginationSupport).emptyPage(query);

        PagedDataDTO<UserTask> result = impl.findUserTasks(query, List.of());

        assertThat(result.getData()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
        verify(queryPaginationSupport).emptyPage(query);
        verify(userTaskRepository, never()).findAll(any(Specification.class), any(PageRequest.class));
        verify(queryPaginationSupport, never()).clampedPage(any(), any(), any());
        verify(queryPaginationSupport, never()).toDTOBulk(any(), any());
    }

    @Test
    void findUserTasks_emptyAllowed_mutationWouldCallRepository() {
        // Named mutation (P-67): if the `allowedPdIds.isEmpty()` guard is removed,
        // findUserTasks(List.of()) must NOT reach the repository — it must return emptyPage.
        UserTaskQuery query = new UserTaskQuery();
        query.setPageIndex(0);
        query.setPageSize(10);
        PagedDataDTO<UserTask> emptyDto = new PagedDataDTO<>();
        emptyDto.setData(List.of());
        emptyDto.setTotalElements(0L);
        doReturn(emptyDto).when(queryPaginationSupport).emptyPage(query);

        impl.findUserTasks(query, List.of());

        verify(queryPaginationSupport).emptyPage(query);
        verify(userTaskRepository, never()).findAll(any(Specification.class), any(PageRequest.class));
    }

    @Test
    void findUserTasks_nullAllowed_doesNotUseEmptyPage() {
        UserTaskQuery query = new UserTaskQuery();
        query.setPageIndex(0);
        query.setPageSize(10);
        UserTaskEntity entity = new UserTaskEntity();
        entity.setId(UUID.randomUUID());
        Page<UserTaskEntity> page = new PageImpl<>(List.of(entity), PageRequest.of(0, 10), 1);
        when(userTaskRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);
        PageRequest clamped = PageRequest.of(0, 10, Sort.by("createdAt").descending());
        when(queryPaginationSupport.clampedPage(any(), any(), any())).thenReturn(clamped);
        PagedDataDTO<UserTask> bulk = new PagedDataDTO<>();
        bulk.setData(List.of(new UserTask()));
        doReturn(bulk).when(queryPaginationSupport).toDTOBulk(any(), any());

        PagedDataDTO<UserTask> result = impl.findUserTasks(query, null);

        verify(queryPaginationSupport, never()).emptyPage(any());
        verify(userTaskRepository).findAll(any(Specification.class), eq(clamped));
        assertThat(result).isNotNull();
    }

    @Test
    void getUserTask_returnsMapped() {
        UUID id = UUID.randomUUID();
        UserTaskEntity entity = new UserTaskEntity();
        entity.setId(id);
        UserTask dto = new UserTask();
        dto.setId(id);
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(entity));
        when(userTaskMapper.toDTO(entity)).thenReturn(dto);

        UserTask result = impl.getUserTask(id);

        assertThat(result).isEqualTo(dto);
        verify(userTaskRepository).findById(id);
        verify(userTaskMapper).toDTO(entity);
    }

    @Test
    void getUserTask_throwsWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(userTaskRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> impl.getUserTask(id))
            .isInstanceOf(java.util.NoSuchElementException.class);

        verify(userTaskRepository).findById(id);
        verify(userTaskMapper, never()).toDTO(any());
    }
}
