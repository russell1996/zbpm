package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** A deployed DMN decision, versioned: each deployment of the same {@code decisionId} adds a new
 *  {@code version}; the highest version is used at evaluation. */
@Getter
@Setter
@Entity
@Table(name = "dmn_definitions")
public class DmnDefinitionEntity {
    @Id
    private UUID id;
    private String decisionId;
    private int version;
    @Lob
    private String dmn;
    private Instant createdAt;
    /** Link to the process definition this DMN belongs to (embedded DMN in BPMN deploy). */
    private UUID processDefinitionId;
    /** WO-C8-18: деплоймент пачки (POST /deployments); null — одиночный деплой (обратная совместимость). */
    private UUID deploymentId;
    /** WO-C8-20: тег версии решения из DMN XML (zeebe:versionTag); null — версия без тега. */
    private String versionTag;
}
