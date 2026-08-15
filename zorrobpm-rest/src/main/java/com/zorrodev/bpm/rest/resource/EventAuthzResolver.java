package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
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
 * Shared AuthZ resolver for read endpoints (ADR-8 / WO-ACL-1, G-L: DENY by default).
 *
 * Two distinct read questions, two explicit methods (the old single {@code resolve()} was
 * the reason they got merged):
 * <ul>
 *   <li>{@link #visibleDefinitionIds} — process DEFINITIONS (list, card, XML, structure,
 *       forms, DMN): any authenticated {@link Principal.UserPrincipal} sees all (null);
 *       {@link Principal.ServicePrincipal} stays grant-scoped (an integration key has no
 *       reason to see foreign models);</li>
 *   <li>{@link #readableRuntimePdIds} — RUNTIME data (instances, tasks, variables,
 *       incidents, events): {@code SUPER_ADMIN} → null; {@link Principal.UserPrincipal} →
 *       processDefinitionIds of processes the user is a MEMBER of (any role, incl. VIEWER);
 *       {@link Principal.ServicePrincipal} → grant-scoped (WO-SEC-54: {@code isFull} never
 *       widens visibility to global).</li>
 * </ul>
 * null = see all (superAdmin only for runtime — WO-SEC-54), empty = see nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventAuthzResolver {

    private final ProcessRepository processRepository;
    private final ProcessDefinitionRepository processDefinitionRepository;
    private final ProcessMemberRepository processMemberRepository;

    /**
     * Resolves the processDefinitionIds whose DEFINITIONS the principal may read.
     * Any authenticated user sees all definitions (ADR-8 §1); ServicePrincipal stays
     * grant-scoped (WO-SEC-54: never null).
     *
     * @return null if the principal sees all definitions,
     *         empty set if the principal sees nothing,
     *         or the set of allowed processDefinitionIds.
     */
    public Collection<UUID> visibleDefinitionIds(Principal principal, String processDefinitionKey) {
        if (principal.isSuperAdmin()) {
            return null; // see all
        }
        if (principal instanceof Principal.ServicePrincipal sp) {
            return resolveByGrants(sp, processDefinitionKey);
        }
        // UserPrincipal — any authenticated user sees all process definitions (ADR-8 §1)
        return null;
    }

    /**
     * Resolves the processDefinitionIds whose RUNTIME data the principal may read.
     * UserPrincipal → membership (any role); ServicePrincipal → grants (WO-SEC-54);
     * SUPER_ADMIN → null.
     *
     * @return null if the principal sees all runtime,
     *         empty set if the principal sees nothing,
     *         or the set of allowed processDefinitionIds.
     */
    public Collection<UUID> readableRuntimePdIds(Principal principal, String processDefinitionKey) {
        if (principal.isSuperAdmin()) {
            return null; // see all
        }
        if (principal instanceof Principal.ServicePrincipal sp) {
            return resolveByGrants(sp, processDefinitionKey);
        }

        // UserPrincipal — DENY unless the user is a member of the process (any role)
        Principal.UserPrincipal up = (Principal.UserPrincipal) principal;
        List<ProcessMemberEntity> memberships = processMemberRepository.findByUserId(up.userId());
        if (memberships.isEmpty()) {
            return Set.of(); // DENY: no membership → see nothing
        }

        Set<UUID> processIds = memberships.stream()
            .map(ProcessMemberEntity::getProcessId)
            .collect(Collectors.toSet());

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

    /**
     * Grant-scoped resolution for ServicePrincipal (shared by both read questions).
     * WO-SEC-54 (CRITICAL S-02): isFull is a per-process flag (full operations INSIDE the
     * granted process), NOT a global see-all grant. It must never widen the visible
     * process list — the granted processIds (sp.grants().keySet()) are the ONLY visible
     * processes, full or not. Global see-all (null) stays exclusive to isSuperAdmin().
     */
    private Collection<UUID> resolveByGrants(Principal.ServicePrincipal sp, String processDefinitionKey) {
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
}