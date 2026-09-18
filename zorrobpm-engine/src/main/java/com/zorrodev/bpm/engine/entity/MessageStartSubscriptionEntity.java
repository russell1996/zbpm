package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A definition-scoped subscription for a message start event: when a message named
 * {@code messageName} is correlated with no target instance, a new instance of
 * {@code processDefinitionId} is started at {@code elementId}. Superseded by newer versions of the
 * same {@code processKey} at deploy time.
 */
@Getter
@Setter
@Entity
@Table(name = "message_start_subscriptions")
public class MessageStartSubscriptionEntity {
    @Id
    private UUID id;
    private String processKey;
    private UUID processDefinitionId;
    private String elementId;
    private String messageName;
    private Instant createdAt;
}
