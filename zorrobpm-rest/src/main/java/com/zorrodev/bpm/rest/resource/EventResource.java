package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.EventQueryService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * WO-DEBT-7 S9 — thin facade over {@link EventQueryService}: auth +
 * {@code EventAuthzResolver} grant intersection, then delegation. All JPA
 * (domain events + process definition lookup for the key filter) lives in the
 * service; there are zero mutations in this slice (pure reads), so there is
 * no transaction boundary to move — both sides declare no
 * {@code @Transactional} (callers are themselves non-transactional read
 * endpoints; proved by absence, as in Slice 8).
 *
 * <p>GET /events — cursor-based pagination over domain events (ADR-7, WO-EVT-3).
 * AuthZ: only events for process-definitions the principal has grants on.
 * (WO-INT-7 contract — window cut happens after filtering, not before.)
 */
@RestController
@RequestMapping("/events")
@RequiredArgsConstructor
public class EventResource {

    private final EventQueryService eventQueryService;
    private final EventAuthzResolver eventAuthzResolver;
    private final HttpServletRequest request;

    @GetMapping
    public ResponseEntity<PagedDataDTO<Map<String, Object>>> getEvents(
            @RequestParam(required = false) Long since,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String processInstanceId,
            @RequestParam(required = false) String processDefinitionKey,
            @RequestParam(required = false) Integer limit) {

        Principal principal = getPrincipal();
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }

        // WO-API-1: отрицательный limit — 400 через явный guard ресурса, а не
        // 500 из глубины query-слоя и не молчаливое обрезание окна.
        if (limit != null && limit < 0) {
            PagedDataDTO<Map<String, Object>> error = new PagedDataDTO<>();
            error.setData(List.of(Map.of(
                "code", "VALIDATION_ERROR",
                "message", "limit must be non-negative")));
            return ResponseEntity.badRequest().body(error);
        }

        // Validate processInstanceId UUID
        UUID piId = null;
        if (processInstanceId != null && !processInstanceId.isBlank()) {
            try {
                piId = UUID.fromString(processInstanceId);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().build();
            }
        }

        // Grant narrowing WITHOUT the key: superAdmin -> null (see all), otherwise the
        // principal's allowed processDefinitionIds (empty -> see nothing).
        Collection<UUID> allowedPdIds = eventAuthzResolver.readableRuntimePdIds(principal, null);

        // WO-INT-7: the key resolves to ids of ALL versions of that definition and is
        // INTERSECTED with the grants here, so every branch of the query filters in SQL.
        List<UUID> keyPdIds = eventQueryService.resolveKeyPdIds(processDefinitionKey);
        Collection<UUID> pdFilter = intersect(allowedPdIds, keyPdIds);
        if (pdFilter != null && pdFilter.isEmpty()) {
            PagedDataDTO<Map<String, Object>> empty = new PagedDataDTO<>();
            empty.setData(List.of());
            empty.setTotalElements(0L);
            return ResponseEntity.ok(empty);
        }

        long cursor = since != null ? since : 0;
        int maxResults = limit != null ? Math.min(limit, 100) : 50;

        List<Map<String, Object>> envelopes = eventQueryService.findEventEnvelopes(
            since != null ? since : 0, pdFilter, piId, type, maxResults);

        boolean hasMore = envelopes.size() > maxResults;
        if (hasMore) {
            envelopes = envelopes.subList(0, maxResults);
        }

        PagedDataDTO<Map<String, Object>> result = new PagedDataDTO<>();
        result.setData(envelopes);
        result.setTotalElements((long) envelopes.size());
        return ResponseEntity.ok(result);
    }

    /** null = unrestricted; intersecting "see all" with a key filter yields the key filter. */
    private Collection<UUID> intersect(Collection<UUID> allowed, Collection<UUID> extra) {
        if (allowed == null) return extra;
        if (extra == null) return allowed;
        Set<UUID> result = new LinkedHashSet<>(allowed);
        result.retainAll(new LinkedHashSet<>(extra));
        return new ArrayList<>(result);
    }

    private Principal getPrincipal() {
        Object attr = request.getAttribute("principal");
        return attr instanceof Principal p ? p : null;
    }
}
