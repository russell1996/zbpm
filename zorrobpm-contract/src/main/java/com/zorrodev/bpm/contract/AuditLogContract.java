package com.zorrodev.bpm.contract;

import com.zorrodev.bpm.contract.dto.AuditLogEntry;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;

import java.util.List;

public interface AuditLogContract {

    @GetExchange("/admin/audit-log")
    List<AuditLogEntry> getAuditLog(
        @RequestParam(required = false) String processKey,
        @RequestParam(required = false) String ownerUserId,
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to
    );
}
