package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.CreateElementBindingDTO;
import com.zorrodev.bpm.contract.dto.ElementBindingDTO;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.ElementBindingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * WO-DEBT-7 S6 — thin facade over {@link ElementBindingService}: auth checks +
 * delegation. All JPA (reads and every {@code .save()}/{@code .delete()})
 * lives in the service, inside this class' transaction (no
 * {@code @Transactional} on the service — same as the original layout, proven
 * by {@code ElementBindingTransactionIT}: a save failure rolls the
 * upsert-delete back). Zero direct persistence imports.
 *
 * <p>{@code listElementBindings} pre-resolves the definition id through the
 * service for its access check, then delegates (the service re-resolves
 * internally, keeping its body verbatim — two extra indexed reads, no
 * behavior delta).
 */
@Service
@RequiredArgsConstructor
public class ElementBindingOperationsImpl implements ElementBindingOperations {

    private final ElementBindingService elementBindingService;
    private final FormAccessSupport formAccessSupport;

    @Transactional
    @Override
    public ElementBindingDTO createElementBinding(String key, CreateElementBindingDTO dto) {
        // SUPER_ADMIN only
        Principal principal = formAccessSupport.getPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (!principal.isSuperAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only SUPER_ADMIN can create element bindings");
        }
        return elementBindingService.createElementBinding(key, dto);
    }

    @Override
    public List<ElementBindingDTO> listElementBindings(String key) {
        UUID pdId = elementBindingService.resolveLatestDefinitionId(key);
        // Authz: deny if principal lacks access to this process definition (G-L)
        formAccessSupport.requirePdAccess(pdId);
        return elementBindingService.listElementBindings(key);
    }

    @Transactional
    @Override
    public void deleteElementBinding(String key, String elementId) {
        Principal principal = formAccessSupport.getPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (!principal.isSuperAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only SUPER_ADMIN can delete element bindings");
        }
        elementBindingService.deleteElementBinding(key, elementId);
    }
}
