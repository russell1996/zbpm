package com.zorrodev.bpm.engine.repository;

import java.time.Instant;
import java.util.UUID;

public interface OutboxEntryView {
    UUID getId();
    String getKind();
    String getStatus();
    boolean isPublished();
    int getAttempts();
    String getLastError();
    Instant getCreatedAt();
}
