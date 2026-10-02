package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** A user task event not yet confirmed by the broker; {@code seq} is the order of publication. */
@Getter
@Setter
@Entity
@Table(name = "user_task_event_outbox")
public class UserTaskEventOutboxEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long seq;
    private UUID eventId;
    private String eventType;
    private UUID userTaskId;
    /** The JSON body of the message, serialized when the event happened. */
    private String payload;
    private Instant createdAt;
    private int attempts;
    private String lastError;
}
