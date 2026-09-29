package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "service_tasks")
public class ServiceTaskEntity {
    @Id
    private UUID id;
    private UUID processInstanceId;
    private UUID processDefinitionId;
    private String bpmnElementId;
    private Instant createdAt;
    private Instant completedAt;
    /** Engine-owned retry budget; a worker failure decrements it, an incident is raised at 0. */
    private Integer retriesRemaining;
    /** WO-EVT-9: stable job identifier (zeebe:taskDefinition type analog), set from BPMN at creation. */
    private String job;
    /**
     * WO-C8-11: index into the element's {@code startListeners} while a listener job is in
     * flight. {@code null} = the normal path (no listeners, or the real job dispatched/awaited).
     */
    private Integer pendingListenerIndex;
    /**
     * WO-C8-11b: index into the element's {@code endListeners} while an end-listener job is in
     * flight. Separate column by design (no magic values in {@code pendingListenerIndex}).
     * {@code null} = the end phase is not running. Start and end phases never overlap: the end
     * index is set only at real-job completion, by which the start index is already null.
     */
    private Integer pendingEndListenerIndex;
}
