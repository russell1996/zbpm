package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.engine.entity.DomainEventEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.repository.DomainEventRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
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
    private final ProcessRepository processRepository;
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
        Collection<UUID> allowedPdIds = resolveAllowedProcessDefinitionIds(principal, processDefinitionKey);
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
            events = domainEventRepository.findSince(cursor, maxResults + 1);
        } else {
            events = domainEventRepository.findSinceForPrincipal(cursor, allowedPdIds, maxResults + 1);
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

    private Collection<UUID> resolveAllowedProcessDefinitionIds(Principal principal, String processDefinitionKey) {
        // SuperAdmin sees all — return null to bypass filter
        if (principal.isSuperAdmin()) {
            return null;
        }

        // ServicePrincipal with full access sees all
        if (principal instanceof Principal.ServicePrincipal sp) {
            boolean hasFullAccess = sp.grants().values().stream()
                .anyMatch(Principal.Grant::isFull);
            if (hasFullAccess) {
                return null;
            }

            // Get definitionKeys from granted processIds
            Set<UUID> processIds = sp.grants().keySet();
            if (processIds.isEmpty()) {
                return Set.of();
            }

            List<ProcessEntity> processes = processRepository.findAllById(processIds);
            Set<String> allKeys = processes.stream()
                .map(ProcessEntity::getDefinitionKey)
                .collect(Collectors.toSet());

            // If processDefinitionKey filter is provided, narrow further
            Set<String> definitionKeys = (processDefinitionKey != null && !processDefinitionKey.isBlank())
                ? allKeys.stream().filter(k -> processDefinitionKey.equals(k)).collect(Collectors.toSet())
                : allKeys;

            if (definitionKeys.isEmpty()) {
                return Set.of();
            }

            // Find processDefinitionIds for these keys
            List<ProcessDefinitionEntity> defs = processDefinitionRepository.findAll(
                (root, query, cb) -> root.get("key").in(definitionKeys));

            return defs.stream()
                .map(ProcessDefinitionEntity::getId)
                .collect(Collectors.toSet());
        }

        // UserPrincipal — simplified: no access unless we add membership check
        return Set.of();
    }

    private Map<String, Object> toEnvelope(DomainEventEntity event) {
        return Map.of(
            "sequence", event.getSequence(),
            "id", event.getId().toString(),
            "type", event.getType(),
            "version", event.getVersion(),
            "occurredAt", event.getOccurredAt().toString(),
            "processDefinitionId", event.getProcessDefinitionId() != null ? event.getProcessDefinitionId().toString() : null,
            "processInstanceId", event.getProcessInstanceId() != null ? event.getProcessInstanceId().toString() : null,
            "elementId", event.getElementId() != null ? event.getElementId() : null,
            "ownerScope", event.getOwnerScope() != null ? event.getOwnerScope() : null,
            "data", event.getData() != null ? event.getData() : Map.of()
        );
    }

    private Principal getPrincipal() {
        Object attr = request.getAttribute("principal");
        return attr instanceof Principal p ? p : null;
    }
}
