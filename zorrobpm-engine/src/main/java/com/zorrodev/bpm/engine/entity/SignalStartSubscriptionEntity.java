package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A definition-scoped subscription for a signal start event: when a signal named {@code signalName}
 * is broadcast, a new instance of {@code processDefinitionId} is started at {@code elementId}. Unlike
 * a message start (1:1), every subscribed definition is started (broadcast). Superseded by newer
 * versions of the same {@code processKey} at deploy time.
 */
@Getter
@Setter
@Entity
@Table(name = "signal_start_subscriptions")
public class SignalStartSubscriptionEntity {
    @Id
    private UUID id;
    private String processKey;
    private UUID processDefinitionId;
    private String elementId;
    private String signalName;
    private Instant createdAt;
}
