package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.dto.SaveElementSchemaDTO;
import com.zorrodev.bpm.contract.dto.SchemaMapDTO;
import com.zorrodev.bpm.contract.dto.SchemaMapElementDTO;
import com.zorrodev.bpm.engine.entity.ElementArtifactBindingEntity;
import com.zorrodev.bpm.engine.entity.FormArtifactKind;
import com.zorrodev.bpm.engine.entity.FormEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.repository.ElementArtifactBindingRepository;
import com.zorrodev.bpm.engine.repository.FormRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WO-DEBT-7 S5: JPA-backed form-schema-map reads and versioned element-schema
 * writes, moved verbatim out of the REST-layer {@code SchemaMapOperationsImpl}
 * (WO-DEBT-4e slice, itself a byte-for-byte move from {@code FormResource}).
 * The REST class stays behind as a thin facade: auth checks + delegation, with
 * {@code @Transactional} kept exactly where it was ({@code saveElementSchema}).
 * Every read and every {@code .save()}/{@code .delete()} lives here, inside
 * the caller's transaction (no {@code @Transactional} of its own — same as the
 * original location, proven by {@code SaveElementSchemaTransactionIT}: a
 * binding-save failure rolls the artifact save and the upsert-delete back).
 *
 * <p>Responsibility: the schema-map aggregate (per-element artifact resolution
 * with global usage flags + versioned artifact writes with start-event binding
 * pinning). Deliberately NOT inside {@code TaskFormDataService} — that one
 * renders task form-data for runtime, this one inventories and versions form
 * schemas; merging them would be god-class drift. Auth decisions stay in the
 * facade (it pre-resolves the definition id via
 * {@link #resolveLatestDefinitionId} for its access check); this service only
 * signals 404/400 for missing/invalid data.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaMapService {

    private final ProcessDefinitionRepository processDefinitionRepository;
    private final FormRepository formRepository;
    private final ElementArtifactBindingRepository bindingRepository;
    private final BpmnService bpmnService;
    private final ObjectMapper objectMapper;
    private final JsonSchemaValidator jsonSchemaValidator;

    /**
     * Latest definition id for a key (404 if missing). The facade resolves this
     * FIRST so its access check runs before any schema data is read; the main
     * methods below resolve again internally, keeping their bodies verbatim.
     */
    public UUID resolveLatestDefinitionId(String key) {
        Integer maxVersion = processDefinitionRepository.findMaxByKey(key)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));
        return processDefinitionRepository.findByKeyAndVersion(key, maxVersion)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"))
            .getId();
    }

    public SchemaMapDTO getSchemaMap(String key) {
        Integer maxVersion = processDefinitionRepository.findMaxByKey(key)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));
        ProcessDefinitionEntity pd = processDefinitionRepository.findByKeyAndVersion(key, maxVersion)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));

        // Parse BPMN to get elements
        var model = bpmnService.getProcessDefinitionModelById(pd.getId());

        // Get bindings for this PD
        List<ElementArtifactBindingEntity> bindings = bindingRepository.findByProcessDefinitionId(pd.getId());
        Map<String, ElementArtifactBindingEntity> bindingByElement = bindings.stream()
            .collect(java.util.stream.Collectors.toMap(ElementArtifactBindingEntity::getElementId, b -> b, (a, b) -> a));

        // WO-AUDIT-3 (P3): resolve this page's artifact keys FIRST so the DB reads
        // below are scoped IN-queries — no findAll() scans, no per-element round-trips.
        // (BPMN models themselves already come from the BpmnServiceImpl Caffeine cache.)
        java.util.Set<String> pageKeys = new java.util.HashSet<>();
        for (var el : model.getElements()) {
            if (el.getType() == com.zorrodev.bpm.engine.bpmn.model.BpmnElementType.USER_TASK
                && el.getExtensions() != null && el.getExtensions().getUserTaskExtension() != null
                && el.getExtensions().getUserTaskExtension().getFormKey() != null) {
                pageKeys.add(el.getExtensions().getUserTaskExtension().getFormKey());
            } else if (el.getType() == com.zorrodev.bpm.engine.bpmn.model.BpmnElementType.START_EVENT) {
                ElementArtifactBindingEntity b = bindingByElement.get(el.getId());
                if (b != null && b.getArtifactKey() != null) {
                    pageKeys.add(b.getArtifactKey());
                }
            }
        }
        // Latest form per key, one query (same semantics as per-key findTop).
        Map<String, FormEntity> latestFormByKey = new java.util.HashMap<>();
        if (!pageKeys.isEmpty()) {
            for (FormEntity f : formRepository.findByFormKeyIn(pageKeys)) {
                latestFormByKey.merge(f.getFormKey(), f, (a, b) ->
                    a.getVersion() >= b.getVersion() ? a : b);
            }
        }

        // WO-VM-9a fix: shared = GLOBALLY — count usage of THIS page's keys across ALL
        // PDs + user-task externalReferences. A key is shared if >1 element (across all
        // processes) uses it. Counting only page keys gives identical flags (other keys
        // are never consulted) without scanning whole tables.
        Map<String, Long> globalArtifactUsage = new java.util.HashMap<>();
        // Count from bindings (all PDs, scoped to page keys)
        if (!pageKeys.isEmpty()) {
            bindingRepository.findByArtifactKeyIn(pageKeys).forEach(b ->
                globalArtifactUsage.merge(b.getArtifactKey(), 1L, Long::sum));
        }
        // Count from user-task externalReferences across all PDs (cached models, no DB)
        for (ProcessDefinitionEntity allPd : processDefinitionRepository.findAll()) {
            try {
                var allModel = bpmnService.getProcessDefinitionModelById(allPd.getId());
                allModel.getElements().stream()
                    .filter(e -> e.getType() == com.zorrodev.bpm.engine.bpmn.model.BpmnElementType.USER_TASK)
                    .forEach(e -> {
                        if (e.getExtensions() != null && e.getExtensions().getUserTaskExtension() != null
                            && e.getExtensions().getUserTaskExtension().getFormKey() != null
                            && pageKeys.contains(e.getExtensions().getUserTaskExtension().getFormKey())) {
                            globalArtifactUsage.merge(e.getExtensions().getUserTaskExtension().getFormKey(), 1L, Long::sum);
                        }
                    });
            } catch (Exception e) {
                log.warn("Failed to parse process definition {} for schema-map usage count: {}", allPd.getId(), e.getMessage());
            }
        }

        List<SchemaMapElementDTO> elements = model.getElements().stream()
            .filter(e -> e.getType() == com.zorrodev.bpm.engine.bpmn.model.BpmnElementType.START_EVENT
                || e.getType() == com.zorrodev.bpm.engine.bpmn.model.BpmnElementType.USER_TASK)
            .map(e -> {
                SchemaMapElementDTO dto = new SchemaMapElementDTO();
                dto.setElementId(e.getId());
                dto.setName(e.getName());
                dto.setType(e.getType().name());

                String artifactKey = null;
                boolean hasExternalReference = false;

                if (e.getType() == com.zorrodev.bpm.engine.bpmn.model.BpmnElementType.USER_TASK) {
                    // User task: formKey from extensions (may be externalReference or formKey)
                    if (e.getExtensions() != null && e.getExtensions().getUserTaskExtension() != null) {
                        artifactKey = e.getExtensions().getUserTaskExtension().getFormKey();
                        hasExternalReference = e.getExtensions().getUserTaskExtension().getExternalReference() != null;
                    }
                } else {
                    // Start event: check binding
                    ElementArtifactBindingEntity binding = bindingByElement.get(e.getId());
                    if (binding != null) {
                        artifactKey = binding.getArtifactKey();
                    }
                }

                dto.setArtifactKey(artifactKey);

                if (artifactKey != null) {
                    // Resolve artifact kind and version (pre-fetched batch above)
                    FormEntity form = latestFormByKey.get(artifactKey);
                    if (form != null) {
                        dto.setKind(form.getKind() != null ? form.getKind().name() : null);
                        dto.setArtifactVersion(form.getVersion());
                    }
                    dto.setShared(globalArtifactUsage.getOrDefault(artifactKey, 0L) > 1);
                }

                dto.setHasExternalReference(hasExternalReference);
                return dto;
            })
            .toList();

        SchemaMapDTO result = new SchemaMapDTO();
        result.setProcessDefinitionKey(pd.getKey());
        result.setVersion(pd.getVersion());
        result.setElements(elements);
        return result;
    }

    public SchemaMapElementDTO saveElementSchema(String key, String elementId, SaveElementSchemaDTO dto) {
        // Validate kind
        if (dto.getKind() == null || dto.getKind().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "kind is required");
        }
        FormArtifactKind kind;
        try {
            kind = FormArtifactKind.valueOf(dto.getKind());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid kind: " + dto.getKind());
        }

        // Validate schema
        if (dto.getSchema() == null || dto.getSchema().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "schema is required");
        }
        try {
            objectMapper.readValue(dto.getSchema(), Object.class);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid JSON");
        }
        if (kind == FormArtifactKind.VARIABLE_SCHEMA) {
            java.util.Set<String> errors = jsonSchemaValidator.validateSchema(dto.getSchema());
            if (!errors.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid JSON Schema: " + String.join("; ", errors));
            }
        }

        // Resolve PD
        Integer maxVersion = processDefinitionRepository.findMaxByKey(key)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));
        ProcessDefinitionEntity pd = processDefinitionRepository.findByKeyAndVersion(key, maxVersion)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found"));

        // Parse BPMN to find element and determine artifactKey
        var model = bpmnService.getProcessDefinitionModelById(pd.getId());
        var element = model.getElements().stream()
            .filter(e -> e.getId().equals(elementId))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Element not found: " + elementId));

        String artifactKey;
        if (element.getType() == com.zorrodev.bpm.engine.bpmn.model.BpmnElementType.USER_TASK) {
            // User task: must have externalReference
            if (element.getExtensions() == null
                || element.getExtensions().getUserTaskExtension() == null
                || element.getExtensions().getUserTaskExtension().getExternalReference() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "User task has no External Form Reference. Set it in Camunda Modeler first.");
            }
            artifactKey = element.getExtensions().getUserTaskExtension().getExternalReference();
        } else {
            // Start event: auto-generate key
            artifactKey = key + ":" + elementId;
        }

        // Create new artifact version
        int maxArtifactVersion = formRepository.findMaxVersionByFormKey(artifactKey);
        FormEntity artifact = new FormEntity();
        artifact.setId(UUID.randomUUID());
        artifact.setFormKey(artifactKey);
        // WO-C8-22: same linked-id extraction as the upload path (null when absent).
        artifact.setFormId(FormEntity.extractFormId(dto.getSchema()));
        // WO-C8-31: same version-tag extraction (null when absent).
        artifact.setVersionTag(FormEntity.extractVersionTag(dto.getSchema()));
        artifact.setVersion(maxArtifactVersion + 1);
        artifact.setKind(kind);
        artifact.setSchemaJson(dto.getSchema());
        artifact.setCreatedAt(Instant.now());
        formRepository.save(artifact);

        // Start event: upsert binding with pinning
        if (element.getType() == com.zorrodev.bpm.engine.bpmn.model.BpmnElementType.START_EVENT) {
            bindingRepository.findByProcessDefinitionIdAndElementId(pd.getId(), elementId)
                .ifPresent(bindingRepository::delete);

            ElementArtifactBindingEntity binding = new ElementArtifactBindingEntity();
            binding.setId(UUID.randomUUID());
            binding.setProcessDefinitionId(pd.getId());
            binding.setProcessDefinitionVersion(pd.getVersion());
            binding.setElementId(elementId);
            binding.setArtifactKey(artifactKey);
            binding.setArtifactVersion(artifact.getVersion());
            binding.setCreatedAt(Instant.now());
            bindingRepository.save(binding);
        }

        // Return updated element status
        SchemaMapElementDTO result = new SchemaMapElementDTO();
        result.setElementId(elementId);
        result.setName(element.getName());
        result.setType(element.getType().name());
        result.setArtifactKey(artifactKey);
        result.setKind(kind.name());
        result.setArtifactVersion(artifact.getVersion());
        result.setHasExternalReference(
            element.getType() == com.zorrodev.bpm.engine.bpmn.model.BpmnElementType.USER_TASK
                && element.getExtensions() != null
                && element.getExtensions().getUserTaskExtension() != null
                && element.getExtensions().getUserTaskExtension().getExternalReference() != null);
        return result;
    }
}
