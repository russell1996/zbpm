package com.zorrodev.bpm.engine.service.query;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.query.MessageSubscriptionQuery;
import com.zorrodev.bpm.contract.model.MessageSubscription;

import java.util.Collection;
import java.util.UUID;

public interface MessageSubscriptionQueryOperations {

    PagedDataDTO<MessageSubscription> findMessageSubscriptions(MessageSubscriptionQuery query, Collection<UUID> allowedPdIds);
}
