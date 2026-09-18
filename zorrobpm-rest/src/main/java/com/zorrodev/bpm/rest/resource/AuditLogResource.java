package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.AuditLogContract;
import com.zorrodev.bpm.contract.dto.AuditLogEntry;
import com.zorrodev.bpm.contract.dto.AuditLogPage;
import com.zorrodev.bpm.engine.repository.AuditLogRepository;
import com.zorrodev.bpm.engine.security.Principal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class AuditLogResource implements AuditLogContract {

    private final AuditLogRepository auditLogRepository;
    private final HttpServletRequest request;

    @Override
    public AuditLogPage getAuditLog(String processKey, String ownerUserId, String from, String to,
                                    String cursor, Integer limit) {
        requireSuperAdmin();

        UUID ownerUuid = null;
        if (ownerUserId != null && !ownerUserId.isBlank()) {
            try { ownerUuid = UUID.fromString(ownerUserId); }
            catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid ownerUserId"); }
        }

        Instant fromTime = parseInstant(from);
        Instant toTime = parseInstant(to);

        // WO-AUDIT-3 (P2): bounded window instead of the whole journal. limit+1 probes hasMore.
        int pageSize = limit == null ? 100 : limit;
        if (pageSize < 1 || pageSize > 1000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid limit (1..1000)");
        }
        Instant cursorAt = null;
        UUID cursorId = null;
        if (cursor != null && !cursor.isBlank()) {
            int sep = cursor.indexOf('|');
            if (sep < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cursor");
            }
            try {
                cursorAt = Instant.parse(cursor.substring(0, sep));
                cursorId = UUID.fromString(cursor.substring(sep + 1));
            } catch (IllegalArgumentException | DateTimeParseException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cursor");
            }
        }

        var window = auditLogRepository.findPage(processKey, ownerUuid, fromTime, toTime,
            cursorAt, cursorId, pageSize + 1);
        boolean hasMore = window.size() > pageSize;
        if (hasMore) {
            window = window.subList(0, pageSize);
        }

        AuditLogPage page = new AuditLogPage();
        page.setEntries(window.stream().map(e -> {
            AuditLogEntry entry = new AuditLogEntry();
            entry.setId(e.getId());
            entry.setPrincipalType(e.getPrincipalType());
            entry.setPrincipalId(e.getPrincipalId());
            entry.setOwnerUserId(e.getOwnerUserId());
            entry.setAction(e.getAction());
            entry.setProcessKey(e.getProcessKey());
            entry.setTargetId(e.getTargetId());
            entry.setAt(e.getAt());
            return entry;
        }).toList());
        page.setHasMore(hasMore);
        if (hasMore) {
            var last = window.get(window.size() - 1);
            page.setNextCursor(last.getAt().toString() + "|" + last.getId());
        }
        return page;
    }

    private void requireSuperAdmin() {
        Object attr = request.getAttribute("principal");
        Principal principal = attr instanceof Principal p ? p : null;
        if (principal == null || !principal.isSuperAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "SUPER_ADMIN required");
        }
    }

    private Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Instant.parse(s); }
        catch (DateTimeParseException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date format: " + s); }
    }
}
