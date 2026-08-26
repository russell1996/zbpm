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
@Table(name = "mail_settings")
public class MailSettingsEntity {

    /** Single-row table: always this well-known id. */
    public static final UUID SINGLE_ROW_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Id
    private UUID id = SINGLE_ROW_ID;

    private String host;
    private Integer port;
    private String username;
    private String passwordEncrypted;
    private String sender;

    @Column(columnDefinition = "text")
    private String allowedRecipients;

    private Instant createdAt;
    private Instant updatedAt;
}
