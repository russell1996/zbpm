package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.engine.entity.DomainEventEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.repository.DomainEventRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.security.Principal;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
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
 * GET /events — cursor-based pagination over domain events (ADR-7, WO-EVT-3).
 * AuthZ: only events for process-definitions the principal has grants on.
 * <p>
 * WO-INT-7: ALL filters (grant narrowing, processDefinitionKey, type, processInstanceId)
 * are composed into the SQL query BEFORE the cursor window is cut. The previous version cut
 * the window over the whole table first and post-filtered in memory — a keyed request on a
 * live database returned EMPTY once foreign events filled the window, and the filter strength
 * depended on who asked (superAdmin got the weak path). Contract unchanged: same parameters,
 * same response shape, same {@code since} cursor semantics.
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
        List<UUID> keyPdIds = resolveKeyPdIds(processDefinitionKey);
        Collection<UUID> pdFilter = intersect(allowedPdIds, keyPdIds);
        if (pdFilter != null && pdFilter.isEmpty()) {
            PagedDataDTO<Map<String, Object>> empty = new PagedDataDTO<>();
            empty.setData(List.of());
            empty.setTotalElements(0L);
            return ResponseEntity.ok(empty);
        }

        long cursor = since != null ? since : 0;
        int maxResults = limit != null ? Math.min(limit, 100) : 50;

        Specification<DomainEventEntity> spec = (root, query, cb) -> cb.greaterThan(root.get("sequence"), cursor);
        final Collection<UUID> pdFilterF = pdFilter;
        if (pdFilter != null) {
            spec = spec.and((root, query, cb) -> root.get("processDefinitionId").in(pdFilterF));
        }
        if (piId != null) {
            final UUID piIdF = piId;
            spec = spec.and((root, query, cb) -> cb.equal(root.get("processInstanceId"), piIdF));
        }
        if (type != null && !type.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("type"), type));
        }

        List<DomainEventEntity> events = domainEventRepository
            .findAll(spec, PageRequest.of(0, maxResults + 100, Sort.by(Sort.Direction.ASC, "sequence")))
            .getContent();

        boolean hasMore = events.size() > maxResults;
        if (hasMore) {
            events = events.subList(0, maxResults);
        }

        List<Map<String, Object>> envelopes = events.stream()
            .map(this::toEnvelope)
            .collect(java.util.stream.Collectors.toList());

        PagedDataDTO<Map<String, Object>> result = new PagedDataDTO<>();
        result.setData(envelopes);
        result.setTotalElements((long) envelopes.size());
        return ResponseEntity.ok(result);
    }

    /** Ids of all versions of the given definition key; null when no key requested. */
    private List<UUID> resolveKeyPdIds(String processDefinitionKey) {
        if (processDefinitionKey == null || processDefinitionKey.isBlank()) {
            return null;
        }
        return processDefinitionRepository.findAll(
                (root, query, cb) -> cb.equal(root.get("key"), processDefinitionKey))
            .stream().map(ProcessDefinitionEntity::getId).toList();
    }

    /** null = unrestricted; intersecting "see all" with a key filter yields the key filter. */
    private Collection<UUID> intersect(Collection<UUID> allowed, Collection<UUID> extra) {
        if (allowed == null) return extra;
        if (extra == null) return allowed;
        Set<UUID> result = new LinkedHashSet<>(allowed);
        result.retainAll(new LinkedHashSet<>(extra));
        return new ArrayList<>(result);
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
