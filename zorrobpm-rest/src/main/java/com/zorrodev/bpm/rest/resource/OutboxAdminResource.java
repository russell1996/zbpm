package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.OutboxAdminContract;
import com.zorrodev.bpm.contract.dto.OutboxEntryDTO;
import com.zorrodev.bpm.engine.entity.OutboxEntry;
import com.zorrodev.bpm.engine.repository.OutboxRepository;
import com.zorrodev.bpm.engine.security.Principal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class OutboxAdminResource implements OutboxAdminContract {

    private static final List<String> KNOWN_STATUSES = List.of("PENDING", "FAILED");

    private final OutboxRepository outboxRepository;
    private final HttpServletRequest request;

    @Override
    public List<OutboxEntryDTO> getOutbox(String status) {
        requireSuperAdmin();
        var page = org.springframework.data.domain.PageRequest.of(0, 100, org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
        List<com.zorrodev.bpm.engine.repository.OutboxEntryView> views;
        if (status == null || status.isBlank()) {
            views = outboxRepository.findProjectedAllOrderByCreatedAtDesc(page);
        } else if (!KNOWN_STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status: " + status);
        } else {
            views = outboxRepository.findProjectedByStatusOrderByCreatedAtDesc(status, page);
        }
        return views.stream().map(OutboxAdminResource::toDTOView).toList();
    }

    @Override
    @Transactional
    public OutboxEntryDTO redriveOutbox(UUID id) {
        requireSuperAdmin();
        OutboxEntry entry = outboxRepository.findById(id).orElse(null);
        if (entry == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Outbox entry not found: " + id);
        }
        // WO-REL-22 (B2): conditional — 1 only if the row was actually FAILED.
        if (outboxRepository.redrive(id) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Outbox entry is not FAILED (status=" + entry.getStatus() + "): " + id);
        }
        OutboxEntry fresh = outboxRepository.findById(id).orElse(null);
        if (fresh == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Outbox entry not found: " + id);
        }
        return toDTO(fresh);
    }

    private static OutboxEntryDTO toDTO(OutboxEntry e) {
        OutboxEntryDTO dto = new OutboxEntryDTO();
        dto.setId(e.getId());
        dto.setKind(e.getKind() != null ? e.getKind().name() : null);
        dto.setStatus(e.getStatus());
        dto.setPublished(e.isPublished());
        dto.setAttempts(e.getAttempts());
        dto.setLastError(e.getLastError());
        dto.setCreatedAt(e.getCreatedAt());
        return dto;
    }

    private static OutboxEntryDTO toDTOView(com.zorrodev.bpm.engine.repository.OutboxEntryView v) {
        OutboxEntryDTO dto = new OutboxEntryDTO();
        dto.setId(v.getId());
        dto.setKind(v.getKind() != null ? v.getKind().toString() : null);
        dto.setStatus(v.getStatus());
        dto.setPublished(v.isPublished());
        dto.setAttempts(v.getAttempts());
        dto.setLastError(v.getLastError());
        dto.setCreatedAt(v.getCreatedAt());
        return dto;
    }

    private void requireSuperAdmin() {
        Object attr = request.getAttribute("principal");
        Principal principal = attr instanceof Principal p ? p : null;
        if (principal == null || !principal.isSuperAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "SUPER_ADMIN required");
        }
    }
}
