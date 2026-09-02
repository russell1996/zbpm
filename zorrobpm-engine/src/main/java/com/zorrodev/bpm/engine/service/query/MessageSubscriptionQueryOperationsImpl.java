package com.zorrodev.bpm.engine.service.query;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.query.MessageSubscriptionQuery;
import com.zorrodev.bpm.contract.model.MessageSubscription;
import com.zorrodev.bpm.engine.entity.MessageSubscriptionEntity;
import com.zorrodev.bpm.engine.mapper.MessageSubscriptionMapper;
import com.zorrodev.bpm.engine.repository.MessageSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MessageSubscriptionQueryOperationsImpl implements MessageSubscriptionQueryOperations {

    private final MessageSubscriptionRepository messageSubscriptionRepository;
    private final MessageSubscriptionMapper messageSubscriptionMapper;
    private final QueryPaginationSupport queryPaginationSupport;

    @Override
    public PagedDataDTO<MessageSubscription> findMessageSubscriptions(MessageSubscriptionQuery query, Collection<UUID> allowedPdIds) {
        List<Specification<MessageSubscriptionEntity>> specifications = new LinkedList<>();
        if (allowedPdIds != null && allowedPdIds.isEmpty()) {
            return queryPaginationSupport.emptyPage(query);
        }
        if (allowedPdIds != null) {
            specifications.add(queryPaginationSupport.processInstanceInAllowedDefinitions(allowedPdIds));
        }
        if (query.getId() != null) {
            specifications.add((root, q, cb) -> cb.equal(root.get("id"), query.getId()));
        }
        if (query.getProcessInstanceId() != null) {
            specifications.add(MessageSubscriptionRepository.byProcessInstanceId(query.getProcessInstanceId()));
        }
        if (query.getConsumed() != null) {
            specifications.add(MessageSubscriptionRepository.byConsumed(query.getConsumed()));
        }
        PageRequest page = queryPaginationSupport.clampedPage(query.getPageIndex(), query.getPageSize(), Sort.by("createdAt").descending());
        return queryPaginationSupport.toDTO(messageSubscriptionRepository.findAll(Specification.allOf(specifications), page), messageSubscriptionMapper::toDTO);
    }
}
