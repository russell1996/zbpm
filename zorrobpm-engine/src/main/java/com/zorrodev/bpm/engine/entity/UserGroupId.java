package com.zorrodev.bpm.engine.entity;

import java.io.Serializable;
import java.util.UUID;

public record UserGroupId(UUID userId, String groupName) implements Serializable {
}
