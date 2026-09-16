package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.ProcessAuthzService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.UUID;

/**
 * WO-DEBT-7 S8 — thin facade over {@link ProcessAuthzService}: delegation,
 * zero direct persistence imports. The service is a Component (Engine module),
 * this resolver is a Component (REST module) — seven REST classes call only
 * this type, so their signatures are untouched (as the dispatch requires).
 * Shared AuthZ resolver for read endpoints (ADR-8 / WO-ACL-1, G-L: DENY by
 * default).
 *
 * Two distinct read questions, two explicit methods (the old single
 * {@code resolve()} was the reason they got merged):
 * <ul>
 *   <li>{@link #visibleDefinitionIds} — process DEFINITIONS (list, card, XML,
 *       structure, forms, DMN): any authenticated
 *       {@link Principal.UserPrincipal} sees all (null);
 *       {@link Principal.ServicePrincipal} stays grant-scoped;</li>
 *   <li>{@link #readableRuntimePdIds} — RUNTIME data (instances, tasks,
 *       variables, incidents, events): {@code SUPER_ADMIN} → null;
 *       {@link Principal.UserPrincipal} → processDefinitionIds of processes
 *       the user is a MEMBER of; {@link Principal.ServicePrincipal} →
 *       grant-scoped.</li>
 * </ul>
 * null = see all (superAdmin only for runtime — WO-SEC-54), empty = see
 * nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventAuthzResolver {

    private final ProcessAuthzService processAuthzService;

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
        return processAuthzService.visibleDefinitionIds(principal, processDefinitionKey);
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
        return processAuthzService.readableRuntimePdIds(principal, processDefinitionKey);
    }

    /**
     * WO-SEC-67 (F13): LIVE view for a service-key stream — re-reads the key's
     * CURRENT grant rows (frozen principal grants go stale on setGrants).
     * Thin delegation, like every other method here.
     *
     * @return null only when the credential itself is dead (caller fails closed);
     *         otherwise the live pdId set (possibly empty).
     */
    public Collection<UUID> readableRuntimePdIdsForKey(UUID apiKeyId, UUID ownerUserId,
            String processDefinitionKey) {
        return processAuthzService.readableRuntimePdIdsForKey(apiKeyId, ownerUserId, processDefinitionKey);
    }
}
