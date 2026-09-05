package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * WO-C8-18: a deployment — a batch of resources (BPMN processes, DMN decisions) laid down
 * atomically by {@code POST /deployments}. Single-resource endpoints leave
 * {@code deployment_id} NULL on their rows (back-compat); the batch stamps one id on all
 * rows it creates. Soft links, no FK (deletion/rollback of a deployment is out of scope).
 */
@Getter
@Setter
@Entity
@Table(name = "deployments")
public class DeploymentEntity {
    @Id
    private UUID id;
    private Instant createdAt;
    /** Principal that performed the deployment (nullable — system/test deploys). */
    private String deployedBy;
    /** Caller-supplied description of the batch (nullable). */
    private String description;
}
