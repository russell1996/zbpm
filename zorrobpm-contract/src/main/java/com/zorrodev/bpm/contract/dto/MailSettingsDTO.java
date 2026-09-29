package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * WO-INT-6: mail settings payload for GET/PUT /admin/mail/settings.
 * Password is write-only: it is never returned by GET. {@code passwordSet} tells the UI
 * whether a password is currently stored.
 */
@Getter
@Setter
public class MailSettingsDTO {
    private String host;
    private Integer port;
    private String username;
    private String password;
    private String from;
    private String allowedRecipients;
    private Boolean passwordSet;
}
