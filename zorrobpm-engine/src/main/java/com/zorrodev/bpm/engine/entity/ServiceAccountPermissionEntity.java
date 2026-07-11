package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@Entity
@IdClass(ServiceAccountPermissionId.class)
@Table(name = "service_account_permission")
public class ServiceAccountPermissionEntity {
    @Id
    private UUID serviceAccountId;
    @Id
    private String permission;
}
