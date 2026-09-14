package com.zorrodev.bpm.engine.service;

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
 * WO-DEBT-7 S8: JPA-backed AuthZ reads for the shared
 * {@code EventAuthzResolver}. Moved verbatim out of the REST-layer resolver
 * (which stays behind as a thin facade: delegation, zero direct persistence
 * imports). Every JPA read lives here; there are zero mutations in this slice
 * ({@code 0} {@code .save()}/{@code .delete()} — pure reads), so there is no
 * transaction boundary to move — both the resolver and this service declare no
 * {@code @Transactional} (callers are themselves either non-transactional or
 * already transactional on unrelated work; moving a boundary that does not exist
 * would be the P-67-style invention warned against in the dispatch).
 *
 * <p>Responsibility: process-level AuthZ visibility (definition-read vs
 * runtime-read). Deliberately NOT inside any of the previous narrow services
 * ({@code ProcessMemberService} owns membership lifecycle/mutations,
 * {@code SchemaMapService}/{@code ElementBindingService} own form/binding
 * artifacts, {@code FormDeploymentService} mints form versions,
 * {@code RuntimeSupportService}/{@code TaskFormDataService}/{@code ApiKeyService}
 * own disjoint domains) — this one answers "which process definitions does this
 * principal see", the exact question seven unrelated REST classes share, and
 * must stay separate to avoid god-class drift.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessAuthzService {

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
