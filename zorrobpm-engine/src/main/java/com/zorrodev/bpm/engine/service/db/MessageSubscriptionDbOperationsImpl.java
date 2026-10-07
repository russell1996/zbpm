package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.engine.dto.MessageStartSubscription;
import com.zorrodev.bpm.engine.dto.MessageSubscription;
import com.zorrodev.bpm.engine.entity.MessageStartSubscriptionEntity;
import com.zorrodev.bpm.engine.entity.MessageSubscriptionEntity;
import com.zorrodev.bpm.engine.repository.MessageStartSubscriptionRepository;
import com.zorrodev.bpm.engine.repository.MessageSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * WO-DEBT-1k: домен MessageSubscriptions — реализация.
 * Перенесено 1:1 из DBServiceImpl (11 методов).
 */
@Service
@RequiredArgsConstructor
public class MessageSubscriptionDbOperationsImpl implements MessageSubscriptionDbOperations {

    private final MessageSubscriptionRepository messageSubscriptionRepository;
    private final MessageStartSubscriptionRepository messageStartSubscriptionRepository;

    @Override
    public UUID createMessageSubscription(UUID processInstanceId, UUID activityId, String messageName) {
        return createMessageSubscription(processInstanceId, activityId, messageName, null, null);
    }

    @Override
    public UUID createMessageSubscription(UUID processInstanceId, UUID activityId, String messageName, String boundaryElementId) {
        return createMessageSubscription(processInstanceId, activityId, messageName, boundaryElementId, null);
    }

    @Override
    public UUID createMessageSubscription(UUID processInstanceId, UUID activityId, String messageName, String boundaryElementId, String correlationKey) {
        UUID id = UUID.randomUUID();
        MessageSubscriptionEntity entity = new MessageSubscriptionEntity();
        entity.setId(id);
        entity.setProcessInstanceId(processInstanceId);
        entity.setActivityId(activityId);
        entity.setMessageName(messageName);
        entity.setConsumed(false);
        entity.setCreatedAt(Instant.now());
        entity.setBoundaryElementId(boundaryElementId);
        entity.setCorrelationKey(correlationKey);
        messageSubscriptionRepository.save(entity);
        return id;
    }

    @Override
    public UUID createEventSubprocessMessageSubscription(UUID processInstanceId, String messageName, String eventSubprocessId) {
        UUID id = UUID.randomUUID();
        MessageSubscriptionEntity entity = new MessageSubscriptionEntity();
        entity.setId(id);
        entity.setProcessInstanceId(processInstanceId);
        entity.setActivityId(null);
        entity.setMessageName(messageName);
        entity.setConsumed(false);
        entity.setCreatedAt(Instant.now());
        entity.setEventSubprocessId(eventSubprocessId);
        messageSubscriptionRepository.save(entity);
        return id;
    }

    @Override
    public void createMessageStartSubscription(String processKey, UUID processDefinitionId, String elementId, String messageName) {
        MessageStartSubscriptionEntity entity = new MessageStartSubscriptionEntity();
        entity.setId(UUID.randomUUID());
        entity.setProcessKey(processKey);
        entity.setProcessDefinitionId(processDefinitionId);
        entity.setElementId(elementId);
        entity.setMessageName(messageName);
        entity.setCreatedAt(Instant.now());
        messageStartSubscriptionRepository.save(entity);
    }

    @Override
    public void deleteMessageStartSubscriptionsByKey(String processKey) {
        messageStartSubscriptionRepository.deleteByProcessKey(processKey);
    }

    @Override
    public void deleteMessageSubscriptionsByProcessInstanceId(UUID processInstanceId) {
        messageSubscriptionRepository.deleteByProcessInstanceId(processInstanceId);
    }

    @Override
    public List<MessageStartSubscription> findMessageStartSubscriptions(String messageName) {
        return messageStartSubscriptionRepository.findByMessageName(messageName).stream()
            .map(e -> {
                MessageStartSubscription sub = new MessageStartSubscription();
                sub.setId(e.getId());
                sub.setProcessKey(e.getProcessKey());
                sub.setProcessDefinitionId(e.getProcessDefinitionId());
                sub.setElementId(e.getElementId());
                sub.setMessageName(e.getMessageName());
                return sub;
            })
            .toList();
    }

    @Override
    public List<MessageSubscription> findMessageSubscriptions(String messageName, UUID processInstanceId, UUID cursorId) {
        List<MessageSubscriptionEntity> entities = processInstanceId != null
            ? (cursorId == null
                ? messageSubscriptionRepository.findFirst500ByConsumedFalseAndMessageNameAndProcessInstanceIdOrderByIdDesc(messageName, processInstanceId)
                : messageSubscriptionRepository.findFirst500ByConsumedFalseAndMessageNameAndProcessInstanceIdAndIdLessThanOrderByIdDesc(messageName, processInstanceId, cursorId))
            : (cursorId == null
                ? messageSubscriptionRepository.findFirst500ByConsumedFalseAndMessageNameOrderByIdDesc(messageName)
                : messageSubscriptionRepository.findFirst500ByConsumedFalseAndMessageNameAndIdLessThanOrderByIdDesc(messageName, cursorId));
        return toMessageSubscriptions(entities);
    }

    @Override
    public List<MessageSubscription> findMessageSubscriptionsByKey(String messageName, String correlationKey, UUID cursorId) {
        List<MessageSubscriptionEntity> entities = cursorId == null
            ? messageSubscriptionRepository.findFirst500ByConsumedFalseAndMessageNameAndCorrelationKeyOrderByIdDesc(messageName, correlationKey)
            : messageSubscriptionRepository.findFirst500ByConsumedFalseAndMessageNameAndCorrelationKeyAndIdLessThanOrderByIdDesc(messageName, correlationKey, cursorId);
        return toMessageSubscriptions(entities);
    }

    /**
     * Legacy entry point — returns ALL unconsumed subscriptions (no batch limit).
     * Used by characterization tests and non-fan-out callers. Fan-out paths use the
     * 3-arg cursor variant ({@link #findMessageSubscriptions(String, UUID, UUID)}) instead.
     */
    @Override
    public List<MessageSubscription> findMessageSubscriptions(String messageName, UUID processInstanceId) {
        List<MessageSubscriptionEntity> entities = processInstanceId != null
            ? messageSubscriptionRepository.findByConsumedFalseAndMessageNameAndProcessInstanceId(messageName, processInstanceId)
            : messageSubscriptionRepository.findByConsumedFalseAndMessageName(messageName);
        return toMessageSubscriptions(entities);
    }

    @Override
    public List<MessageSubscription> findMessageSubscriptionsByKey(String messageName, String correlationKey) {
        return toMessageSubscriptions(
            messageSubscriptionRepository.findByConsumedFalseAndMessageNameAndCorrelationKey(messageName, correlationKey));
    }

    private List<MessageSubscription> toMessageSubscriptions(List<MessageSubscriptionEntity> entities) {
        return entities.stream()
            .map(e -> {
                MessageSubscription sub = new MessageSubscription();
                sub.setId(e.getId());
                sub.setProcessInstanceId(e.getProcessInstanceId());
                sub.setActivityId(e.getActivityId());
                sub.setMessageName(e.getMessageName());
                sub.setBoundaryElementId(e.getBoundaryElementId());
                sub.setEventSubprocessId(e.getEventSubprocessId());
                return sub;
            })
            .toList();
    }

    @Override
    @Transactional
    public boolean consumeMessageSubscription(UUID subscriptionId) {
        // WO-SEC-59 #2: CAS — only one concurrent correlation may consume the subscription.
        // A plain findById+save would let two concurrent callers both observe "not consumed"
        // and both apply the signal/message (double branch on a non-interrupting boundary).
        return messageSubscriptionRepository.markConsumed(subscriptionId) == 1;
    }
}
