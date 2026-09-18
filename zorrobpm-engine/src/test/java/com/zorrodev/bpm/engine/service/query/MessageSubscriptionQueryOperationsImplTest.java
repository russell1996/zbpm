package com.zorrodev.bpm.engine.service.query;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.query.MessageSubscriptionQuery;
import com.zorrodev.bpm.contract.model.MessageSubscription;
import com.zorrodev.bpm.engine.entity.MessageSubscriptionEntity;
import com.zorrodev.bpm.engine.mapper.MessageSubscriptionMapper;
import com.zorrodev.bpm.engine.repository.MessageSubscriptionRepository;
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
class MessageSubscriptionQueryOperationsImplTest {

    @Mock private MessageSubscriptionRepository messageSubscriptionRepository;
    @Mock private MessageSubscriptionMapper messageSubscriptionMapper;
    @Mock private QueryPaginationSupport queryPaginationSupport;
    @InjectMocks private MessageSubscriptionQueryOperationsImpl impl;

    @Test
    void findMessageSubscriptions_withAllowedPdIds_appliesFilterAndDelegatesToRepository() {
        MessageSubscriptionQuery query = new MessageSubscriptionQuery();
        query.setPageIndex(0);
        query.setPageSize(10);
        UUID allowedPdId = UUID.randomUUID();

        MessageSubscriptionEntity entity = new MessageSubscriptionEntity();
        entity.setId(UUID.randomUUID());
        Page<MessageSubscriptionEntity> page = new PageImpl<>(List.of(entity), PageRequest.of(0, 10), 1);
        when(messageSubscriptionRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);
        MessageSubscription dto = new MessageSubscription();
        dto.setId(entity.getId());
        PageRequest clamped = PageRequest.of(0, 10, Sort.by("createdAt").descending());
        when(queryPaginationSupport.clampedPage(0, 10, Sort.by("createdAt").descending())).thenReturn(clamped);
        PagedDataDTO<MessageSubscription> dtoResult = new PagedDataDTO<>();
        dtoResult.setData(List.of(dto));
        dtoResult.setTotalElements(1L);
        when(queryPaginationSupport.processInstanceInAllowedDefinitions(List.of(allowedPdId))).thenReturn((root, q, cb) -> cb.conjunction());
        doReturn(dtoResult).when(queryPaginationSupport).toDTO(eq(page), any());

        PagedDataDTO<MessageSubscription> result = impl.findMessageSubscriptions(query, List.of(allowedPdId));

        ArgumentCaptor<Specification<MessageSubscriptionEntity>> specCaptor = ArgumentCaptor.forClass(Specification.class);
        verify(messageSubscriptionRepository).findAll(specCaptor.capture(), eq(clamped));
        Specification<MessageSubscriptionEntity> captured = specCaptor.getValue();
        assertThat(captured).isNotNull();

        verify(queryPaginationSupport).processInstanceInAllowedDefinitions(List.of(allowedPdId));
        verify(queryPaginationSupport).clampedPage(0, 10, Sort.by("createdAt").descending());
        verify(messageSubscriptionRepository).findAll(any(Specification.class), eq(clamped));
        assertThat(result.getData()).containsExactly(dto);
    }

    @Test
    void findMessageSubscriptions_emptyAllowed_returnsEmptyPageAndDoesNotCallRepository() {
        MessageSubscriptionQuery query = new MessageSubscriptionQuery();
        query.setPageIndex(0);
        query.setPageSize(10);
        PagedDataDTO<MessageSubscription> emptyDto = new PagedDataDTO<>();
        emptyDto.setData(List.of());
        emptyDto.setTotalElements(0L);
        emptyDto.setPageIndex(0);
        emptyDto.setPageSize(10);
        doReturn(emptyDto).when(queryPaginationSupport).emptyPage(query);

        PagedDataDTO<MessageSubscription> result = impl.findMessageSubscriptions(query, List.of());

        assertThat(result.getData()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
        verify(queryPaginationSupport).emptyPage(query);
        verify(messageSubscriptionRepository, never()).findAll(any(Specification.class), any(PageRequest.class));
        verify(queryPaginationSupport, never()).clampedPage(any(), any(), any());
        verify(queryPaginationSupport, never()).processInstanceInAllowedDefinitions(any());
    }

    @Test
    void findMessageSubscriptions_emptyAllowed_mutationWouldCallRepository() {
        // Named mutation (P-67): if the `allowedPdIds.isEmpty()` guard is removed,
        // findMessageSubscriptions(List.of()) must NOT reach the repository — it must return emptyPage.
        MessageSubscriptionQuery query = new MessageSubscriptionQuery();
        query.setPageIndex(0);
        query.setPageSize(10);
        PagedDataDTO<MessageSubscription> emptyDto = new PagedDataDTO<>();
        emptyDto.setData(List.of());
        emptyDto.setTotalElements(0L);
        doReturn(emptyDto).when(queryPaginationSupport).emptyPage(query);

        impl.findMessageSubscriptions(query, List.of());

        verify(queryPaginationSupport).emptyPage(query);
        verify(messageSubscriptionRepository, never()).findAll(any(Specification.class), any(PageRequest.class));
    }

    @Test
    void findMessageSubscriptions_nullAllowed_doesNotUseEmptyPage() {
        MessageSubscriptionQuery query = new MessageSubscriptionQuery();
        query.setPageIndex(0);
        query.setPageSize(10);
        MessageSubscriptionEntity entity = new MessageSubscriptionEntity();
        entity.setId(UUID.randomUUID());
        Page<MessageSubscriptionEntity> page = new PageImpl<>(List.of(entity), PageRequest.of(0, 10), 1);
        when(messageSubscriptionRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);
        PageRequest clamped = PageRequest.of(0, 10, Sort.by("createdAt").descending());
        when(queryPaginationSupport.clampedPage(any(), any(), any())).thenReturn(clamped);
        PagedDataDTO<MessageSubscription> dtoResult = new PagedDataDTO<>();
        dtoResult.setData(List.of(new MessageSubscription()));
        doReturn(dtoResult).when(queryPaginationSupport).toDTO(any(), any());

        PagedDataDTO<MessageSubscription> result = impl.findMessageSubscriptions(query, null);

        verify(queryPaginationSupport, never()).emptyPage(any());
        verify(messageSubscriptionRepository).findAll(any(Specification.class), eq(clamped));
        verify(queryPaginationSupport, never()).processInstanceInAllowedDefinitions(any());
        assertThat(result).isNotNull();
    }

    @Test
    void findMessageSubscriptions_createdAtSorting_used() {
        MessageSubscriptionQuery query = new MessageSubscriptionQuery();
        query.setPageIndex(1);
        query.setPageSize(20);
        Page<MessageSubscriptionEntity> page = new PageImpl<>(List.of(), PageRequest.of(1, 20), 0);
        when(messageSubscriptionRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);
        PageRequest clamped = PageRequest.of(1, 20, Sort.by("createdAt").descending());
        when(queryPaginationSupport.clampedPage(1, 20, Sort.by("createdAt").descending())).thenReturn(clamped);
        PagedDataDTO<MessageSubscription> expected = new PagedDataDTO<>();
        expected.setData(List.of());
        doReturn(expected).when(queryPaginationSupport).toDTO(eq(page), any());

        impl.findMessageSubscriptions(query, null);

        verify(queryPaginationSupport).clampedPage(1, 20, Sort.by("createdAt").descending());
        verify(queryPaginationSupport).toDTO(eq(page), any());
    }
}
