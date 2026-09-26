package com.zorrodev.bpm.contract;

import com.zorrodev.bpm.contract.dto.AuditLogEntry;
import com.zorrodev.bpm.contract.dto.AuditLogPage;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;

public interface AuditLogContract {

    @GetExchange("/admin/audit-log")
    AuditLogPage getAuditLog(
        @RequestParam(required = false) String processKey,
        @RequestParam(required = false) String ownerUserId,
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to,
        @RequestParam(required = false) String cursor,
        @RequestParam(required = false) Integer limit
    );
}
