package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Convert;
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
@Table(name = "element_listener_phase")
public class ElementListenerPhaseEntity {
    /**
     * WO-C8-25: carrier id — doubles as the {@code serviceTaskId} of the dispatched
     * listener jobs, so worker completions route back here with no activity row involved.
     */
    @Id
    private UUID id;
    private UUID processInstanceId;
    private UUID tokenId;
    private String bpmnElementId;
    /** Phase kind — only {@code START} in this WO; column reserved for future phases. */
    @Convert(converter = ListenerPhaseConverter.class)
    private ListenerPhase phase;
    /** Index of the in-flight listener; null = no phase (row only exists in flight). */
    private Integer listenerIndex;
    /** Durable retry budget of the in-flight listener job (model value, default 3). */
    private Integer retriesRemaining;
    private Instant createdAt;
}
