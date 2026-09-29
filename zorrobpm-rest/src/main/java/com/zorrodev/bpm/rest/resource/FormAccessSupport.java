package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.security.Principal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * WO-DEBT-4b — shared cross-cutting authz helpers for the FormResource slice
 * (two-level authz model: DEFINITION-read vs RUNTIME-read). Byte-for-byte copy of
 * the 5 private methods from {@code FormResource} (visibility private → public
 * is the only change — a shared bean must be callable); all javadoc kept
 * verbatim (WO-ACL-1 ×2, security-relevant). Add-only foundation: no endpoint
 * logic moves here.
 */
@Component
@RequiredArgsConstructor
public class FormAccessSupport {

    private final HttpServletRequest request;
    private final EventAuthzResolver eventAuthzResolver;

    /**
     * WO-ACL-1: form artifacts (list/get/start-form/bindings/schema-map) are DEFINITION
     * reads — any authenticated user sees them (ADR-8 §1); ServicePrincipal stays
     * grant-scoped.
     */
    public Collection<UUID> resolveAllowedPdIds() {
        Object attr = request.getAttribute("principal");
        if (!(attr instanceof Principal principal)) {
            return Set.of();
        }
        return eventAuthzResolver.visibleDefinitionIds(principal, null);
    }

    /**
     * WO-ACL-1: the task form of a concrete process instance is RUNTIME data — it is
     * resolved per instance and prefilled with the instance's variables. Only process
     * members (or SUPER_ADMIN / granted ServicePrincipal) may read it.
     */
    public Collection<UUID> resolveRuntimePdIds() {
        Object attr = request.getAttribute("principal");
        if (!(attr instanceof Principal principal)) {
            return Set.of();
        }
        return eventAuthzResolver.readableRuntimePdIds(principal, null);
    }

    public void requirePdAccess(UUID pdId) {
        Collection<UUID> allowed = resolveAllowedPdIds();
        if (allowed != null && !allowed.contains(pdId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
    }

    public void requireRuntimePdAccess(UUID pdId) {
        Collection<UUID> allowed = resolveRuntimePdIds();
        if (allowed != null && !allowed.contains(pdId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
    }

    public Principal getPrincipal() {
        Object attr = request.getAttribute("principal");
        return attr instanceof Principal p ? p : null;
    }
}
