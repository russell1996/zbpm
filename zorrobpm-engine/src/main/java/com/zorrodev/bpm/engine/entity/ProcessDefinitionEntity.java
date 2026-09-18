package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name="process_definitions")
public class ProcessDefinitionEntity {

    /** WO-REL-15: deployment is in progress (version row written, artifacts not yet all created). */
    public static final String STATE_PENDING = "PENDING";
    /** WO-REL-15: fully deployed — version + model + subscriptions + jobs + bindings all present. */
    public static final String STATE_ACTIVE = "ACTIVE";
    /** WO-REL-15: previous deployment attempt failed; redeploy of the same sha256 repairs it. */
    public static final String STATE_FAILED = "FAILED";

    @Id
    private UUID id;
    @Column(name = "code")
    private String key;
    private String name;
    private Integer version;
    @Column(unique = true)
    private String sha256;
    private Instant createdAt;
    private String startFormKey;
    /** WO-C8-3: zeebe:versionTag процесса (nullable) — для bindingType="versionTag" на call activity. */
    private String versionTag;
    /**
     * WO-ENG-17: Camunda-style historyTimeToLive в днях (nullable) — срок чистки
     * завершённых инстансов ЭТОГО определения. NULL = наследовать глобальный
     * {@code zorrobpm.engine.retention.ttl-days} (обратная совместимость §4 WO).
     */
    private Integer historyTimeToLiveDays;
    /** WO-C8-18: деплоймент пачки (POST /deployments); null — одиночный деплой (обратная совместимость). */
    private UUID deploymentId;
    @Column(name = "deployment_state", nullable = false)
    private String deploymentState = STATE_ACTIVE;
}
