package com.zorrodev.bpm.engine.entity;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "activities")
public class ActivityEntity {
    @Id
    private UUID id;
    private UUID processInstanceId;
    private String bpmnElementId;
    private Instant createdAt;
    private Instant completedAt;
    @Enumerated(EnumType.STRING)
    private BpmnElementType type;
    @Enumerated(EnumType.STRING)
    private ActivityStatus status;
    private UUID token;
    /**
     * WO-C8-21r2: index of the in-flight creating task listener (moved here from the
     * {@code user_tasks} marker row of round 1 — no {@code user_tasks} row exists until
     * the task is really created). Null = no phase in flight. Never magic values.
     */
    private Integer pendingCreatingListenerIndex;
    /**
     * WO-C8-21r2: remaining retries of the current creating-listener job (durable
     * per-listener budget; start/end listeners reuse {@code retriesRemaining} on their
     * {@code service_tasks} row, which a user task never has).
     */
    private Integer creatingListenerRetriesRemaining;
}
