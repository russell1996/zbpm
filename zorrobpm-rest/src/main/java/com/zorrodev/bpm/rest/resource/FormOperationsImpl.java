package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.DeployFormDTO;
import com.zorrodev.bpm.contract.dto.FormDTO;
import com.zorrodev.bpm.engine.entity.ElementArtifactBindingEntity;
import com.zorrodev.bpm.engine.entity.FormArtifactKind;
import com.zorrodev.bpm.engine.entity.FormEntity;
import com.zorrodev.bpm.engine.repository.ElementArtifactBindingRepository;
import com.zorrodev.bpm.engine.repository.FormRepository;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.JsonSchemaValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * WO-DEBT-4e — Forms domain slice. Byte-for-byte move of the 3 endpoints + 3
 * domain helpers from {@code FormResource} (only {@code getPrincipal()}/
 * {@code resolveAllowedPdIds()} re-pointed at {@link FormAccessSupport});
 * {@code @Transactional} moved with {@code deployForm}, read methods stay
 * non-transactional as in the original.
 */
@Service
@RequiredArgsConstructor
public class FormOperationsImpl implements FormOperations {

    private final FormRepository formRepository;
    private final ElementArtifactBindingRepository bindingRepository;
    private final ObjectMapper objectMapper;
    private final JsonSchemaValidator jsonSchemaValidator;
    private final FormAccessSupport formAccessSupport;
    private final com.zorrodev.bpm.engine.service.AdvisoryDeployLock advisoryDeployLock;

    @Override
    public List<FormDTO> listForms() {
        Collection<UUID> allowedPdIds = formAccessSupport.resolveAllowedPdIds();

        // Get all form keys that are bound to allowed process definitions
        Set<String> allowedFormKeys;
        if (allowedPdIds == null) {
            // SuperAdmin / full grant — see all forms
            allowedFormKeys = null; // null = no filter
        } else if (allowedPdIds.isEmpty()) {
            // No grants — only show unbound forms (not tied to any process)
            allowedFormKeys = getUnboundFormKeys();
        } else {
            allowedFormKeys = getFormKeysBoundToPds(allowedPdIds);
            // Also include unbound forms
            allowedFormKeys.addAll(getUnboundFormKeys());
        }

        return formRepository.findLatestVersions().stream()
            .filter(entity -> allowedFormKeys == null || allowedFormKeys.contains(entity.getFormKey()))
            .map(entity -> {
                FormDTO dto = new FormDTO();
                dto.setKey(entity.getFormKey());
                dto.setVersion(entity.getVersion());
                dto.setKind(entity.getKind() != null ? entity.getKind().name() : null);
                dto.setSchema(entity.getSchemaJson());
                return dto;
            }).toList();
    }

    @Transactional
    @Override
    public FormDTO deployForm(DeployFormDTO dto) {
        // WO-FORM-1: only SUPER_ADMIN can deploy forms
        Principal principal = formAccessSupport.getPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (!principal.isSuperAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Deploy requires SUPER_ADMIN");
        }

        if (dto.getKey() == null || dto.getKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Form key is required");
        }
        if (dto.getKind() == null || dto.getKind().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "kind is required");
        }
        FormArtifactKind kind;
        try {
            kind = FormArtifactKind.valueOf(dto.getKind());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid kind: " + dto.getKind() + ". Must be FORM_JS or VARIABLE_SCHEMA");
        }
        if (dto.getSchema() == null || dto.getSchema().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Form schema is required");
        }

        // Validate JSON
        try {
            objectMapper.readValue(dto.getSchema(), Object.class);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid JSON");
        }

        // WO-VM-5: validate JSON Schema structure for VARIABLE_SCHEMA
        if (kind == FormArtifactKind.VARIABLE_SCHEMA) {
            java.util.Set<String> errors = jsonSchemaValidator.validateSchema(dto.getSchema());
            if (!errors.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid JSON Schema: " + String.join("; ", errors));
            }
        }

        // WO-SCALE-1: serialize concurrent deploys of the same formKey on the same PG xact
        advisoryDeployLock.acquireForKey("form:" + dto.getKey());
        // Versioning: version = max + 1
        int maxVersion = formRepository.findMaxVersionByFormKey(dto.getKey());
        FormEntity entity = new FormEntity();
        entity.setId(UUID.randomUUID());
        entity.setFormKey(dto.getKey());
        // WO-C8-22: a Modeler-linked .form carries its id in the schema JSON — store it so
        // user tasks referencing formId resolve (null when the JSON has no id: key path as before).
        entity.setFormId(FormEntity.extractFormId(dto.getSchema()));
        // WO-C8-31: top-level "versionTag" of the same JSON (WO-C8-27; lenient, null when absent).
        entity.setVersionTag(FormEntity.extractVersionTag(dto.getSchema()));
        entity.setVersion(maxVersion + 1);
        entity.setKind(kind);
        entity.setSchemaJson(dto.getSchema());
        entity.setCreatedAt(Instant.now());
        formRepository.save(entity);

        FormDTO result = new FormDTO();
        result.setKey(entity.getFormKey());
        result.setVersion(entity.getVersion());
        result.setKind(entity.getKind().name());
        result.setSchema(entity.getSchemaJson());
        return result;
    }

    @Override
    public FormDTO getForm(String key) {
        FormEntity entity = formRepository.findTopByFormKeyOrderByVersionDesc(key)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Form not found"));

        // Authz: 404 if form belongs to an inaccessible process (G-L: deny by default)
        checkFormAccess(key);

        FormDTO dto = new FormDTO();
        dto.setKey(entity.getFormKey());
        dto.setVersion(entity.getVersion());
        dto.setKind(entity.getKind() != null ? entity.getKind().name() : null);
        dto.setSchema(entity.getSchemaJson());
        return dto;
    }

    /**
     * Returns form keys that are NOT bound to any process definition (global forms).
     * These are visible to all authenticated principals regardless of process-level grants.
     */
    private Set<String> getUnboundFormKeys() {
        // All form keys that appear in at least one binding
        Set<String> boundKeys = bindingRepository.findAll().stream()
            .map(ElementArtifactBindingEntity::getArtifactKey)
            .collect(Collectors.toSet());
        // All form keys from the form repository
        Set<String> allKeys = formRepository.findLatestVersions().stream()
            .map(FormEntity::getFormKey)
            .collect(Collectors.toSet());
        // Unbound = all keys minus bound keys
        Set<String> unbound = new java.util.HashSet<>(allKeys);
        unbound.removeAll(boundKeys);
        return unbound;
    }

    /**
     * Returns form keys that are bound to at least one of the given process definition IDs.
     */
    private Set<String> getFormKeysBoundToPds(Collection<UUID> pdIds) {
        return bindingRepository.findByProcessDefinitionIdIn(pdIds).stream()
            .map(ElementArtifactBindingEntity::getArtifactKey)
            .collect(Collectors.toSet());
    }

    /**
     * Checks that the current principal has access to the process definitions
     * associated with the given form key. Throws 404 if denied (G-L: deny by default).
     * Unbound forms (not tied to any process) are accessible to all authenticated principals.
     */
    private void checkFormAccess(String formKey) {
        Collection<UUID> allowedPdIds = formAccessSupport.resolveAllowedPdIds();
        if (allowedPdIds == null) {
            return; // superAdmin / full grant — see all
        }
        // Find process definitions this form is bound to
        List<UUID> boundPdIds = bindingRepository.findByArtifactKey(formKey).stream()
            .map(ElementArtifactBindingEntity::getProcessDefinitionId)
            .distinct()
            .toList();
        if (boundPdIds.isEmpty()) {
            return; // unbound form — accessible to all authenticated principals
        }
        boolean hasAccess = boundPdIds.stream().anyMatch(allowedPdIds::contains);
        if (!hasAccess) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
    }
}
