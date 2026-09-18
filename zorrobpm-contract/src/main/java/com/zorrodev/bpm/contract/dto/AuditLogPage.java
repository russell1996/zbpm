package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * WO-AUDIT-3 (P2): cursor page over the audit journal — {@code hasMore} +
 * opaque {@code nextCursor} instead of a counted total. The journal is unbounded;
 * a {@code COUNT(*)} total per page does not scale with it.
 */
@Getter
@Setter
public class AuditLogPage {
    private List<AuditLogEntry> entries;
    private boolean hasMore;
    private String nextCursor;
}
