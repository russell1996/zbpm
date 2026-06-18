package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A token parked at a message catch event, waiting for a message with {@code messageName} to be
 * correlated to its {@code processInstanceId}. When correlated, {@code activityId} is signalled.
 */
@Getter
@Setter
@Entity
@Table(name = "message_subscriptions")
public class MessageSubscriptionEntity {
    @Id
    private UUID id;
    private UUID processInstanceId;
    private UUID activityId;
    private String messageName;
    private boolean consumed;
    private Instant createdAt;
    /** Non-null when this subscription is a message boundary event: the boundary element to fire
     *  (interrupting/non-interrupting) instead of signalling {@code activityId} as a catch. */
    private String boundaryElementId;
}
