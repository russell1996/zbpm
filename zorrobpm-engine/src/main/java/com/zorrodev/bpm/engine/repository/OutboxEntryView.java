package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.OutboxKind;
import com.zorrodev.bpm.engine.entity.OutboxStatus;

import java.time.Instant;
import java.util.UUID;

public interface OutboxEntryView {
    UUID getId();
    OutboxKind getKind();
    OutboxStatus getStatus();
    boolean isPublished();
    int getAttempts();
    String getLastError();
    Instant getCreatedAt();
}
