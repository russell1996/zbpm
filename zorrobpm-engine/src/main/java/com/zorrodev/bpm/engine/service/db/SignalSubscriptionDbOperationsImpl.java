package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.engine.dto.SignalStartSubscription;
import com.zorrodev.bpm.engine.dto.SignalSubscription;
import com.zorrodev.bpm.engine.entity.SignalStartSubscriptionEntity;
import com.zorrodev.bpm.engine.entity.SignalSubscriptionEntity;
import com.zorrodev.bpm.engine.repository.SignalStartSubscriptionRepository;
import com.zorrodev.bpm.engine.repository.SignalSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * WO-DEBT-1l: домен SignalSubscriptions — реализация.
 * Перенесено 1:1 из DBServiceImpl (8 методов).
 */
@Service
@RequiredArgsConstructor
public class SignalSubscriptionDbOperationsImpl implements SignalSubscriptionDbOperations {

    private final SignalSubscriptionRepository signalSubscriptionRepository;
    private final SignalStartSubscriptionRepository signalStartSubscriptionRepository;

    @Override
    public UUID createSignalSubscription(UUID processInstanceId, UUID activityId, String signalName) {
        return createSignalSubscription(processInstanceId, activityId, signalName, null);
    }

    @Override
    public UUID createSignalSubscription(UUID processInstanceId, UUID activityId, String signalName, String boundaryElementId) {
        UUID id = UUID.randomUUID();
        SignalSubscriptionEntity entity = new SignalSubscriptionEntity();
        entity.setId(id);
        entity.setProcessInstanceId(processInstanceId);
        entity.setActivityId(activityId);
        entity.setSignalName(signalName);
        entity.setConsumed(false);
        entity.setCreatedAt(Instant.now());
        entity.setBoundaryElementId(boundaryElementId);
        signalSubscriptionRepository.save(entity);
        return id;
    }

    @Override
    public UUID createEventSubprocessSignalSubscription(UUID processInstanceId, String signalName, String eventSubprocessId) {
        UUID id = UUID.randomUUID();
        SignalSubscriptionEntity entity = new SignalSubscriptionEntity();
        entity.setId(id);
        entity.setProcessInstanceId(processInstanceId);
        entity.setActivityId(null);
        entity.setSignalName(signalName);
        entity.setConsumed(false);
        entity.setCreatedAt(Instant.now());
        entity.setEventSubprocessId(eventSubprocessId);
        signalSubscriptionRepository.save(entity);
        return id;
    }

    @Override
    public void createSignalStartSubscription(String processKey, UUID processDefinitionId, String elementId, String signalName) {
        SignalStartSubscriptionEntity entity = new SignalStartSubscriptionEntity();
        entity.setId(UUID.randomUUID());
        entity.setProcessKey(processKey);
        entity.setProcessDefinitionId(processDefinitionId);
        entity.setElementId(elementId);
        entity.setSignalName(signalName);
        entity.setCreatedAt(Instant.now());
        signalStartSubscriptionRepository.save(entity);
    }

    @Override
    public void deleteSignalStartSubscriptionsByKey(String processKey) {
        signalStartSubscriptionRepository.deleteByProcessKey(processKey);
    }

    @Override
    public List<SignalStartSubscription> findSignalStartSubscriptions(String signalName) {
        return signalStartSubscriptionRepository.findBySignalName(signalName).stream()
            .map(e -> {
                SignalStartSubscription sub = new SignalStartSubscription();
                sub.setId(e.getId());
                sub.setProcessKey(e.getProcessKey());
                sub.setProcessDefinitionId(e.getProcessDefinitionId());
                sub.setElementId(e.getElementId());
                sub.setSignalName(e.getSignalName());
                return sub;
            })
            .toList();
    }

    @Override
    public List<SignalSubscription> findSignalSubscriptions(String signalName) {
        return signalSubscriptionRepository.findByConsumedFalseAndSignalName(signalName).stream()
            .map(e -> {
                SignalSubscription sub = new SignalSubscription();
                sub.setId(e.getId());
                sub.setProcessInstanceId(e.getProcessInstanceId());
                sub.setActivityId(e.getActivityId());
                sub.setSignalName(e.getSignalName());
                sub.setBoundaryElementId(e.getBoundaryElementId());
                sub.setEventSubprocessId(e.getEventSubprocessId());
                return sub;
            })
            .toList();
    }

    /**
     * WO-REL-31 CR-3: keyset-paged variant — returns up to 500 unconsumed subscriptions
     * in deterministic id-DESC order, enabling batched fan-out without OOM.
     */
    @Override
    public List<SignalSubscription> findSignalSubscriptions(String signalName, UUID cursorId) {
        List<SignalSubscriptionEntity> entities = cursorId == null
            ? signalSubscriptionRepository.findFirst500ByConsumedFalseAndSignalNameOrderByIdDesc(signalName)
            : signalSubscriptionRepository.findFirst500ByConsumedFalseAndSignalNameAndIdLessThanOrderByIdDesc(signalName, cursorId);
        return entities.stream()
            .map(e -> {
                SignalSubscription sub = new SignalSubscription();
                sub.setId(e.getId());
                sub.setProcessInstanceId(e.getProcessInstanceId());
                sub.setActivityId(e.getActivityId());
                sub.setSignalName(e.getSignalName());
                sub.setBoundaryElementId(e.getBoundaryElementId());
                sub.setEventSubprocessId(e.getEventSubprocessId());
                return sub;
            })
            .toList();
    }

    @Override
    @Transactional
    public boolean consumeSignalSubscription(UUID subscriptionId) {
        // WO-SEC-59 #2: CAS — only one concurrent correlation may consume the subscription.
        return signalSubscriptionRepository.markConsumed(subscriptionId) == 1;
    }
}
