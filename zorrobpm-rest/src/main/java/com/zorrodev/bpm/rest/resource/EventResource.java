package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.engine.entity.DomainEventEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.repository.DomainEventRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.security.Principal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * GET /events — cursor-based pagination over domain events (ADR-7, WO-EVT-3).
 * AuthZ: only events for process-definitions the principal has grants on.
 */
@RestController
@RequestMapping("/events")
@RequiredArgsConstructor
public class EventResource {

    private final DomainEventRepository domainEventRepository;
    private final ProcessDefinitionRepository processDefinitionRepository;
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

        // Resolve allowed processDefinitionIds based on grants (null = see all)
        Collection<UUID> allowedPdIds = eventAuthzResolver.resolve(principal, processDefinitionKey);
        if (allowedPdIds != null && allowedPdIds.isEmpty()) {
            PagedDataDTO<Map<String, Object>> empty = new PagedDataDTO<>();
            empty.setData(List.of());
            empty.setTotalElements(0L);
            return ResponseEntity.ok(empty);
        }

        long cursor = since != null ? since : 0;
        int maxResults = limit != null ? Math.min(limit, 100) : 50;

        List<DomainEventEntity> events;
        if (allowedPdIds == null) {
            // Full access — no filter
            events = domainEventRepository.findSince(cursor, maxResults + 100);
        } else {
            events = domainEventRepository.findSinceForPrincipal(cursor, allowedPdIds, maxResults + 100);
        }

        // Apply type filter in memory (not in SQL for simplicity)
        if (type != null && !type.isBlank()) {
            events = events.stream()
                .filter(e -> type.equals(e.getType()))
                .collect(Collectors.toList());
        }

        // Apply processDefinitionKey filter in memory if needed (for superAdmin/full-access)
        if (processDefinitionKey != null && !processDefinitionKey.isBlank() && allowedPdIds == null) {
            List<UUID> filteredPdIds = processDefinitionRepository.findAll(
                (root, query, cb) -> cb.equal(root.get("key"), processDefinitionKey))
                .stream().map(ProcessDefinitionEntity::getId).collect(Collectors.toList());
            events = events.stream()
                .filter(e -> filteredPdIds.contains(e.getProcessDefinitionId()))
                .collect(Collectors.toList());
        }

        boolean hasMore = events.size() > maxResults;
        if (hasMore) {
            events = events.subList(0, maxResults);
        }

        List<Map<String, Object>> envelopes = events.stream()
            .map(this::toEnvelope)
            .collect(Collectors.toList());

        PagedDataDTO<Map<String, Object>> result = new PagedDataDTO<>();
        result.setData(envelopes);
        result.setTotalElements((long) envelopes.size());
        return ResponseEntity.ok(result);
    }

    private Map<String, Object> toEnvelope(DomainEventEntity event) {
        Map<String, Object> envelope = new java.util.LinkedHashMap<>();
        envelope.put("sequence", event.getSequence());
        envelope.put("id", event.getId().toString());
        envelope.put("type", event.getType());
        envelope.put("version", event.getVersion());
        envelope.put("occurredAt", event.getOccurredAt().toString());
        if (event.getProcessDefinitionId() != null) envelope.put("processDefinitionId", event.getProcessDefinitionId().toString());
        if (event.getProcessInstanceId() != null) envelope.put("processInstanceId", event.getProcessInstanceId().toString());
        if (event.getElementId() != null) envelope.put("elementId", event.getElementId());
        if (event.getOwnerScope() != null) envelope.put("ownerScope", event.getOwnerScope());
        envelope.put("data", event.getData() != null ? event.getData() : Map.of());
        return envelope;
    }

    private Principal getPrincipal() {
        Object attr = request.getAttribute("principal");
        return attr instanceof Principal p ? p : null;
    }
}
