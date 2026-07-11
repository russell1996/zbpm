package com.zorrodev.bpm.engine.entity;

import java.io.Serializable;
import java.util.UUID;

public record ServiceAccountPermissionId(UUID serviceAccountId, String permission) implements Serializable {
}
