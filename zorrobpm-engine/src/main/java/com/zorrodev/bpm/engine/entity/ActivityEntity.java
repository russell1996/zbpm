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
    /**
     * WO-C8-24: index of the in-flight completing task listener (mirror of the creating
     * pair above). Null = no phase in flight. The activity row is real here (the task
     * exists) — no marker-row problem by construction.
     */
    private Integer pendingCompletingListenerIndex;
    /**
     * WO-C8-24: remaining retries of the current completing-listener job (durable
     * per-listener budget, mirror of the creating pair above).
     */
    private Integer completingListenerRetriesRemaining;
    /**
     * WO-C8-28: index of the in-flight assigning task listener (mirror of the
     * creating/completing pairs above — separate column per event, never magic
     * values; null = no phase in flight). The assignment itself parks in
     * {@link #pendingAssignee} until the last listener completes.
     */
    private Integer pendingAssigningListenerIndex;
    /**
     * WO-C8-28: remaining retries of the current assigning-listener job (durable
     * per-listener budget, mirror of the pairs above).
     */
    private Integer assigningListenerRetriesRemaining;
    /**
     * WO-C8-28: assignee parked while the assigning phase runs (model assignee at
     * activation, requested assignee at assign/claim). Applied by the resume tail;
     * the task row keeps its previous (or null) assignee meanwhile.
     */
    private String pendingAssignee;
    /**
     * WO-C8-28: index of the in-flight updating task listener (mirror of the pairs
     * above). Opens on complete-with-variables only — the only variable-write path
     * (no standalone task-variables endpoint exists).
     */
    private Integer pendingUpdatingListenerIndex;
    /**
     * WO-C8-28: remaining retries of the current updating-listener job (durable
     * per-listener budget, mirror of the pairs above).
     */
    private Integer updatingListenerRetriesRemaining;
    /**
     * WO-C8-28: index of the in-flight canceling task listener (mirror of the pairs
     * above). The cancellation tail (boundary continuation / process-cancel tail)
     * waits for the last listener; deny is not supported by Camunda semantics.
     */
    private Integer pendingCancelingListenerIndex;
    /**
     * WO-C8-28: remaining retries of the current canceling-listener job (durable
     * per-listener budget, mirror of the pairs above).
     */
    private Integer cancelingListenerRetriesRemaining;
    /**
     * WO-C8-28: boundary element whose continuation the canceling phase defers
     * (per-token last-closer runs it). Null = process-cancel path (the deferred
     * tail is the process-cancel tail, no boundary involved).
     */
    private String pendingCancelBoundaryElementId;
}
