package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.engine.dto.SignalSubscription;
import com.zorrodev.bpm.engine.entity.SignalStartSubscriptionEntity;
import com.zorrodev.bpm.engine.entity.SignalSubscriptionEntity;
import com.zorrodev.bpm.engine.repository.SignalStartSubscriptionRepository;
import com.zorrodev.bpm.engine.repository.SignalSubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SignalSubscriptionDbOperationsImplTest {

    @Mock private SignalSubscriptionRepository signalSubscriptionRepository;
    @Mock private SignalStartSubscriptionRepository signalStartSubscriptionRepository;
    @InjectMocks private SignalSubscriptionDbOperationsImpl db;

    @Test
    void createSignalSubscription_saves() {
        UUID pi = UUID.randomUUID(); UUID act = UUID.randomUUID();
        UUID id = db.createSignalSubscription(pi, act, "s");
        assertThat(id).isNotNull();
        ArgumentCaptor<SignalSubscriptionEntity> captor = ArgumentCaptor.forClass(SignalSubscriptionEntity.class);
        verify(signalSubscriptionRepository).save(captor.capture());
        assertThat(captor.getValue().getSignalName()).isEqualTo("s");
    }

    @Test
    void createSignalSubscription_withBoundary_saves() {
        UUID pi = UUID.randomUUID(); UUID act = UUID.randomUUID();
        UUID id = db.createSignalSubscription(pi, act, "s", "b");
        assertThat(id).isNotNull();
        ArgumentCaptor<SignalSubscriptionEntity> captor = ArgumentCaptor.forClass(SignalSubscriptionEntity.class);
        verify(signalSubscriptionRepository).save(captor.capture());
        assertThat(captor.getValue().getBoundaryElementId()).isEqualTo("b");
    }

    @Test
    void createEventSubprocessSignalSubscription_saves() {
        UUID pi = UUID.randomUUID();
        UUID id = db.createEventSubprocessSignalSubscription(pi, "s", "esp");
        assertThat(id).isNotNull();
        ArgumentCaptor<SignalSubscriptionEntity> captor = ArgumentCaptor.forClass(SignalSubscriptionEntity.class);
        verify(signalSubscriptionRepository).save(captor.capture());
        assertThat(captor.getValue().getEventSubprocessId()).isEqualTo("esp");
    }

    @Test
    void findSignalSubscriptions_returnsMapped() {
        SignalSubscriptionEntity e = new SignalSubscriptionEntity(); e.setId(UUID.randomUUID());
        when(signalSubscriptionRepository.findByConsumedFalseAndSignalName("s")).thenReturn(List.of(e));
        List<SignalSubscription> result = db.findSignalSubscriptions("s");
        assertThat(result).hasSize(1);
    }

    @Test
    void createSignalStartSubscription_saves() {
        UUID pd = UUID.randomUUID();
        db.createSignalStartSubscription("key", pd, "el", "s");
        ArgumentCaptor<SignalStartSubscriptionEntity> captor = ArgumentCaptor.forClass(SignalStartSubscriptionEntity.class);
        verify(signalStartSubscriptionRepository).save(captor.capture());
        assertThat(captor.getValue().getSignalName()).isEqualTo("s");
    }

    @Test
    void deleteSignalStartSubscriptionsByKey_deletes() {
        db.deleteSignalStartSubscriptionsByKey("key");
        verify(signalStartSubscriptionRepository).deleteByProcessKey("key");
    }

    @Test
    void findSignalStartSubscriptions_returnsMapped() {
        SignalStartSubscriptionEntity e = new SignalStartSubscriptionEntity(); e.setId(UUID.randomUUID());
        when(signalStartSubscriptionRepository.findBySignalName("s")).thenReturn(List.of(e));
        List<?> result = db.findSignalStartSubscriptions("s");
        assertThat(result).hasSize(1);
    }

    @Test
    void consumeSignalSubscription_returnsTrueWhenConsumed() {
        UUID id = UUID.randomUUID();
        when(signalSubscriptionRepository.markConsumed(id)).thenReturn(1);
        assertThat(db.consumeSignalSubscription(id)).isTrue();
    }

    @Test
    void consumeSignalSubscription_returnsFalseWhenAlreadyConsumed() {
        UUID id = UUID.randomUUID();
        when(signalSubscriptionRepository.markConsumed(id)).thenReturn(0);
        assertThat(db.consumeSignalSubscription(id)).isFalse();
    }
}
