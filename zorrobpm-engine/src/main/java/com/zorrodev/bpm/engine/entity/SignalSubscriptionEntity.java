package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A token parked at a signal catch event, waiting for a signal named {@code signalName}. Unlike a
 * message (1:1), a signal is broadcast: a single throw wakes <em>all</em> active subscriptions with
 * the matching name, across process instances.
 */
@Getter
@Setter
@Entity
@Table(name = "signal_subscriptions")
public class SignalSubscriptionEntity {
    @Id
    private UUID id;
    private UUID processInstanceId;
    private UUID activityId;
    private String signalName;
    private boolean consumed;
    private Instant createdAt;
}
