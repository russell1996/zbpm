package com.zorrodev.bpm.engine.entity;

import java.io.Serializable;
import java.util.UUID;

public record ProcessMemberId(UUID processId, UUID userId) implements Serializable {
}
