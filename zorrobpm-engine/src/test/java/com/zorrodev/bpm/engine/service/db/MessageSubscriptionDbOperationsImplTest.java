package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.engine.dto.MessageSubscription;
import com.zorrodev.bpm.engine.entity.MessageStartSubscriptionEntity;
import com.zorrodev.bpm.engine.entity.MessageSubscriptionEntity;
import com.zorrodev.bpm.engine.repository.MessageStartSubscriptionRepository;
import com.zorrodev.bpm.engine.repository.MessageSubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageSubscriptionDbOperationsImplTest {

    @Mock private MessageSubscriptionRepository messageSubscriptionRepository;
    @Mock private MessageStartSubscriptionRepository messageStartSubscriptionRepository;
    @InjectMocks private MessageSubscriptionDbOperationsImpl db;

    @Test
    void createMessageSubscription_saves() {
        UUID pi = UUID.randomUUID(); UUID act = UUID.randomUUID();
        UUID id = db.createMessageSubscription(pi, act, "m");
        assertThat(id).isNotNull();
        ArgumentCaptor<MessageSubscriptionEntity> captor = ArgumentCaptor.forClass(MessageSubscriptionEntity.class);
        verify(messageSubscriptionRepository).save(captor.capture());
        assertThat(captor.getValue().getMessageName()).isEqualTo("m");
    }

    @Test
    void createMessageSubscription_withBoundary_saves() {
        UUID pi = UUID.randomUUID(); UUID act = UUID.randomUUID();
        UUID id = db.createMessageSubscription(pi, act, "m", "b");
        assertThat(id).isNotNull();
        ArgumentCaptor<MessageSubscriptionEntity> captor = ArgumentCaptor.forClass(MessageSubscriptionEntity.class);
        verify(messageSubscriptionRepository).save(captor.capture());
        assertThat(captor.getValue().getBoundaryElementId()).isEqualTo("b");
    }

    @Test
    void createEventSubprocessMessageSubscription_saves() {
        UUID pi = UUID.randomUUID();
        UUID id = db.createEventSubprocessMessageSubscription(pi, "m", "esp");
        assertThat(id).isNotNull();
        ArgumentCaptor<MessageSubscriptionEntity> captor = ArgumentCaptor.forClass(MessageSubscriptionEntity.class);
        verify(messageSubscriptionRepository).save(captor.capture());
        assertThat(captor.getValue().getEventSubprocessId()).isEqualTo("esp");
        assertThat(captor.getValue().getActivityId()).isNull();
    }

    @Test
    void findMessageSubscriptions_returnsMapped() {
        MessageSubscriptionEntity e = new MessageSubscriptionEntity(); e.setId(UUID.randomUUID()); e.setProcessInstanceId(UUID.randomUUID()); e.setActivityId(UUID.randomUUID()); e.setMessageName("m");
        when(messageSubscriptionRepository.findByConsumedFalseAndMessageName("m")).thenReturn(List.of(e));
        List<MessageSubscription> result = db.findMessageSubscriptions("m", null);
        assertThat(result).hasSize(1);
    }

    @Test
    void findMessageSubscriptionsByKey_returnsMapped() {
        MessageSubscriptionEntity e = new MessageSubscriptionEntity(); e.setId(UUID.randomUUID());
        when(messageSubscriptionRepository.findByConsumedFalseAndMessageNameAndCorrelationKey("m", "ck")).thenReturn(List.of(e));
        List<MessageSubscription> result = db.findMessageSubscriptionsByKey("m", "ck");
        assertThat(result).hasSize(1);
    }

    @Test
    void deleteMessageSubscriptionsByProcessInstanceId_deletes() {
        UUID pi = UUID.randomUUID();
        db.deleteMessageSubscriptionsByProcessInstanceId(pi);
        verify(messageSubscriptionRepository).deleteByProcessInstanceId(pi);
    }

    @Test
    void createMessageStartSubscription_saves() {
        UUID pd = UUID.randomUUID();
        db.createMessageStartSubscription("key", pd, "el", "m");
        ArgumentCaptor<MessageStartSubscriptionEntity> captor = ArgumentCaptor.forClass(MessageStartSubscriptionEntity.class);
        verify(messageStartSubscriptionRepository).save(captor.capture());
        assertThat(captor.getValue().getProcessKey()).isEqualTo("key");
    }

    @Test
    void deleteMessageStartSubscriptionsByKey_deletes() {
        db.deleteMessageStartSubscriptionsByKey("key");
        verify(messageStartSubscriptionRepository).deleteByProcessKey("key");
    }

    @Test
    void findMessageStartSubscriptions_returnsMapped() {
        MessageStartSubscriptionEntity e = new MessageStartSubscriptionEntity(); e.setId(UUID.randomUUID());
        when(messageStartSubscriptionRepository.findByMessageName("m")).thenReturn(List.of(e));
        List<?> result = db.findMessageStartSubscriptions("m");
        assertThat(result).hasSize(1);
    }

    @Test
    void consumeMessageSubscription_returnsTrueWhenConsumed() {
        UUID id = UUID.randomUUID();
        when(messageSubscriptionRepository.markConsumed(id)).thenReturn(1);
        assertThat(db.consumeMessageSubscription(id)).isTrue();
        verify(messageSubscriptionRepository).markConsumed(id);
    }

    @Test
    void consumeMessageSubscription_returnsFalseWhenAlreadyConsumed() {
        UUID id = UUID.randomUUID();
        when(messageSubscriptionRepository.markConsumed(id)).thenReturn(0);
        assertThat(db.consumeMessageSubscription(id)).isFalse();
    }
}
