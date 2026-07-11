package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@IdClass(ProcessMemberId.class)
@Table(name = "process_member")
public class ProcessMemberEntity {
    @Id
    private UUID processId;
    @Id
    private UUID userId;
    private String role;
    private UUID addedBy;
    private Instant addedAt;
}
