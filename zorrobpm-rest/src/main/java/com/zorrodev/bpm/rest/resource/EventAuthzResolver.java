package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.security.Principal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Shared AuthZ resolver for event endpoints (WO-EVT-4, G-L: DENY by default).
 * Resolves which processDefinitionIds a principal may see based on their grants.
 * null = see all (superAdmin / full-access ServicePrincipal), empty = see nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventAuthzResolver {

    private final ProcessRepository processRepository;
    private final ProcessDefinitionRepository processDefinitionRepository;

    /**
     * Resolves allowed processDefinitionIds for the given principal.
     *
     * @return null if the principal sees all (superAdmin or full grant),
     *         empty set if the principal sees nothing,
     *         or the set of allowed processDefinitionIds.
     */
    public Collection<UUID> resolve(Principal principal, String processDefinitionKey) {
        if (principal.isSuperAdmin()) {
            return null; // see all
        }

        if (principal instanceof Principal.ServicePrincipal sp) {
            boolean hasFullAccess = sp.grants().values().stream()
                .anyMatch(Principal.Grant::isFull);
            if (hasFullAccess) {
                return null; // see all
            }
            // Get definitionKeys from granted processIds
            Set<UUID> processIds = sp.grants().keySet();
            if (processIds.isEmpty()) {
                return Set.of(); // DENY: no grants → see nothing
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
                return Set.of(); // DENY: no matching keys
            }

            // Find processDefinitionIds for these keys
            List<ProcessDefinitionEntity> defs = processDefinitionRepository.findAll(
                (root, query, cb) -> root.get("key").in(definitionKeys));

            return defs.stream()
                .map(ProcessDefinitionEntity::getId)
                .collect(Collectors.toSet());
        }

        // UserPrincipal — DENY: no access unless we add membership check
        return Set.of();
    }
}
