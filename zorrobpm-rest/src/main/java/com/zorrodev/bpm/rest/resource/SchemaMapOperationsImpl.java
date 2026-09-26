package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.SaveElementSchemaDTO;
import com.zorrodev.bpm.contract.dto.SchemaMapDTO;
import com.zorrodev.bpm.contract.dto.SchemaMapElementDTO;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.SchemaMapService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * WO-DEBT-7 S5 — thin facade over {@link SchemaMapService}: auth checks +
 * delegation. All JPA (reads and every {@code .save()}/{@code .delete()})
 * lives in the service, inside this class' transaction (no
 * {@code @Transactional} on the service — same as the original layout, proven
 * by {@code SaveElementSchemaTransactionIT}: a binding-save failure rolls the
 * artifact save and the upsert-delete back). Zero direct persistence imports.
 *
 * <p>{@code getSchemaMap} pre-resolves the definition id through the service
 * for its access check, then delegates (the service re-resolves internally,
 * keeping its body verbatim — two extra indexed reads, no behavior delta).
 */
@Service
@RequiredArgsConstructor
public class SchemaMapOperationsImpl implements SchemaMapOperations {

    private final SchemaMapService schemaMapService;
    private final FormAccessSupport formAccessSupport;

    @Override
    public SchemaMapDTO getSchemaMap(String key) {
        UUID pdId = schemaMapService.resolveLatestDefinitionId(key);
        // WO-SEC-59 #7: authz first — a principal without access to the process
        // must not read its form schema.
        formAccessSupport.requirePdAccess(pdId);
        return schemaMapService.getSchemaMap(key);
    }

    @Transactional
    @Override
    public SchemaMapElementDTO saveElementSchema(String key, String elementId, SaveElementSchemaDTO dto) {
        // SUPER_ADMIN only
        Principal principal = formAccessSupport.getPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (!principal.isSuperAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only SUPER_ADMIN can save element schemas");
        }
        return schemaMapService.saveElementSchema(key, elementId, dto);
    }
}
