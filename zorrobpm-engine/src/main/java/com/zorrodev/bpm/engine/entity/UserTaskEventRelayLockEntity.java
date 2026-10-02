package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** The one row the publishing node locks, so that one node publishes the outbox at a time. */
@Getter
@Setter
@Entity
@Table(name = "user_task_event_relay_lock")
public class UserTaskEventRelayLockEntity {
    public static final int ID = 1;

    @Id
    private Integer id;
}
