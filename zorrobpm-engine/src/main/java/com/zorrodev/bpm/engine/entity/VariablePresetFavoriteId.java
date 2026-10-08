package com.zorrodev.bpm.engine.entity;

import java.io.Serializable;
import java.util.UUID;

public record VariablePresetFavoriteId(UUID userId, UUID presetId) implements Serializable {
}
