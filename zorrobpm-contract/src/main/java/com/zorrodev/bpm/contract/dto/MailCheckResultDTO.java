package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * WO-INT-8: result of a pre-save SMTP connectivity check (POST /admin/mail/check).
 * No email is sent — this only opens and closes an SMTP transport against the values currently
 * in the form (which may not be saved yet). {@code errorCode} is null on success; the frontend
 * never receives a ready-made message from the backend, only a code it localizes itself (G18).
 */
@Getter
@Setter
public class MailCheckResultDTO {
    private boolean reachable;
    private String errorCode;
}
