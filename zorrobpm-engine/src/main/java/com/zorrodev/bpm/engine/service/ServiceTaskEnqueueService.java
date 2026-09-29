package com.zorrodev.bpm.engine.service;

import java.util.UUID;

public interface ServiceTaskEnqueueService {

    void enqueueAfterCommit(UUID serviceTaskId);

    /**
     * WO-C8-25: dispatches the in-flight listener job of an element-listener phase
     * (gateways/events — no activity row exists, the phase row is the anchor).
     * Same outbox/job protocol as {@link #enqueueAfterCommit}, resolved from the phase.
     */
    void enqueuePhaseListener(UUID phaseId);

}
